#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "libbluray/decoders/graphics_controller.h"
#include "libbluray/decoders/ig.h"
#include "libbluray/decoders/textst.h"
#include "libbluray/decoders/graphics_processor.h"
#include "libbluray/hdmv/mobj_data.h"
#include "libbluray/hdmv/hdmv_insn.h"
#include "libbluray/keys.h"

#define GC_TRACE(...) do { if (0) fprintf(stderr, __VA_ARGS__); } while (0)
#define GC_ERROR(...) GC_TRACE(__VA_ARGS__)
enum { PSR_SELECTED_BUTTON_ID, PSR_MENU_PAGE_ID, BTN_SELECTED };
struct graphics_controller_s {
    uint32_t *regs;
    PG_DISPLAY_SET *igs;
    struct { unsigned enabled_button; } bog_data[MAX_NUM_BOGS];
    unsigned ig_open, ig_drawn, valid_mouse_position, mouse_button_id, popup_visible;
    uint16_t mouse_x, mouse_y;
    unsigned button_effect_running, pointer_route_pending, pointer_target_page;
    unsigned pointer_target_button, pointer_route_steps;
    uint64_t pointer_route_deadline;
    void *in_effects, *out_effects;
};
static uint64_t now;
static uint64_t bd_get_scr(void) { return now; }
static uint32_t bd_psr_read(uint32_t *regs, unsigned index) { return regs[index]; }
static BD_IG_PAGE *_find_page(BD_IG_INTERACTIVE_COMPOSITION *ic, unsigned id)
{
    for (unsigned i = 0; i < ic->num_pages; i++) if (ic->page[i].id == id) return &ic->page[i];
    return NULL;
}
static BD_IG_BUTTON *_find_button_bog(BD_IG_BOG *bog, unsigned id)
{
    for (unsigned i = 0; i < bog->num_buttons; i++) if (bog->button[i].id == id) return &bog->button[i];
    return NULL;
}
static BD_IG_BUTTON *_find_button_page(BD_IG_PAGE *page, unsigned id, unsigned *bog)
{
    for (unsigned i = 0; i < page->num_bogs; i++) {
        BD_IG_BUTTON *button = _find_button_bog(&page->bog[i], id);
        if (button) { if (bog) *bog = i; return button; }
    }
    return NULL;
}
static void _select_button(GRAPHICS_CONTROLLER *gc, uint32_t id) { gc->regs[PSR_SELECTED_BUTTON_ID] = id; }
static int _render_page(GRAPHICS_CONTROLLER *gc, unsigned activated, GC_NAV_CMDS *cmds)
{
    BD_IG_PAGE *page = _find_page(&gc->igs->ics->interactive_composition, gc->regs[PSR_MENU_PAGE_ID]);
    BD_IG_BUTTON *selected = _find_button_page(page, gc->regs[PSR_SELECTED_BUTTON_ID], NULL);
    if (selected && selected->auto_action_flag && activated != selected->id && cmds) {
        cmds->num_nav_cmds = selected->num_nav_cmds;
        cmds->nav_cmds = selected->nav_cmds;
    }
    return 1;
}
static void _reset_user_timeout(GRAPHICS_CONTROLLER *gc) { (void)gc; }
static BD_PG_OBJECT *_find_object_for_button(PG_DISPLAY_SET *set, const BD_IG_BUTTON *button, int state, void *unused)
{
    (void)set; (void)state; (void)unused;
    static BD_PG_OBJECT object = {.width = 50, .height = 50};
    static BD_PG_OBJECT background = {.width = 400, .height = 80};
    if (button->selected_start_object_id_ref == 83) return &background;
    return button->selected_start_object_id_ref == 0xffff ? NULL : &object;
}
#include "menu_route_under_test.h"

struct fixture {
    GRAPHICS_CONTROLLER gc;
    uint32_t regs[2];
    PG_DISPLAY_SET set;
    BD_IG_INTERACTIVE ig;
    BD_IG_PAGE pages[3];
    BD_IG_BOG bogs[3][8];
    BD_IG_BUTTON buttons[3][8];
    MOBJ_CMD action, back, other_back, transport[2], register_back[4], nop;
};
static MOBJ_CMD set_page(unsigned page, unsigned button)
{
    return (MOBJ_CMD){.insn = {.grp = INSN_GROUP_SET, .sub_grp = SET_SETSYSTEM,
                              .set_opt = INSN_SET_BUTTON_PAGE, .op_cnt = 2, .imm_op1 = 1, .imm_op2 = 1},
                      .dst = 0x80000000 | button, .src = 0x80000000 | page};
}
static BD_IG_BUTTON make_button(unsigned id, unsigned x, unsigned y, unsigned object, MOBJ_CMD *cmd, int automatic)
{
    return (BD_IG_BUTTON){.id = id, .x_pos = x, .y_pos = y, .auto_action_flag = automatic,
        .upper_button_id_ref = id, .lower_button_id_ref = id, .left_button_id_ref = id, .right_button_id_ref = id,
        .normal_start_object_id_ref = object, .selected_start_object_id_ref = object, .activated_start_object_id_ref = object,
        .num_nav_cmds = cmd ? 1 : 0, .nav_cmds = cmd};
}
static void enter_page(struct fixture *f, unsigned page, unsigned selected)
{
    f->regs[PSR_MENU_PAGE_ID] = f->pages[page].id;
    f->regs[PSR_SELECTED_BUTTON_ID] = selected;
    for (unsigned i = 0; i < f->pages[page].num_bogs; i++)
        f->gc.bog_data[i].enabled_button = f->pages[page].bog[i].default_valid_button_id_ref;
}
static void init(struct fixture *f)
{
    memset(f, 0, sizeof(*f));
    now = 90000;
    f->action = set_page(77, 8);
    f->back = set_page(41, 100);
    f->other_back = set_page(42, 100);
    f->buttons[0][0] = make_button(100, 100, 100, 11, &f->action, 0);
    f->buttons[0][1] = make_button(101, 250, 100, 21, &f->action, 0);
    f->buttons[1][0] = make_button(8, 10, 40, 101, &f->action, 0);
    f->buttons[1][0].lower_button_id_ref = 99;
    f->buttons[1][1] = make_button(60, 100, 100, 11, NULL, 0);
    f->buttons[1][2] = make_button(61, 250, 100, 21, NULL, 0);
    f->buttons[1][3] = make_button(99, 0, 0, 0xffff, &f->back, 1);
    f->buttons[2][0] = f->buttons[0][0];
    f->buttons[2][1] = f->buttons[0][1];
    for (unsigned p = 0; p < 3; p++) {
        f->pages[p] = (BD_IG_PAGE){.id = p == 1 ? 77 : 41 + (p == 2), .num_bogs = p == 1 ? 4 : 2,
                                 .default_selected_button_id_ref = p == 1 ? 8 : 100, .bog = f->bogs[p]};
        for (unsigned i = 0; i < 8; i++) f->bogs[p][i] = (BD_IG_BOG){
            .num_buttons = 1, .button = &f->buttons[p][i], .default_valid_button_id_ref = f->buttons[p][i].id};
    }
    f->ig.interactive_composition = (BD_IG_INTERACTIVE_COMPOSITION){.num_pages = 3, .page = f->pages};
    f->set.ics = &f->ig;
    f->gc = (GRAPHICS_CONTROLLER){.regs = f->regs, .igs = &f->set, .ig_open = 1, .ig_drawn = 1};
    enter_page(f, 1, 8);
}
static BD_IG_BUTTON *find(struct fixture *f, const BD_IG_BUTTON *copy)
{
    BD_IG_PAGE *parent;
    BD_IG_BUTTON *target;
    return _find_authored_return(&f->gc, &f->pages[1], copy, &parent, &target);
}
static void add_transport(struct fixture *f)
{
    f->transport[0] = (MOBJ_CMD){.insn = {.grp = INSN_GROUP_SET, .sub_grp = SET_SETSYSTEM,
        .set_opt = INSN_ENABLE_BUTTON, .imm_op1 = 1, .op_cnt = 1}, .dst = 30};
    f->transport[1] = set_page(77, 30);
    f->transport[1].src = 0; /* current page, selected button only */
    f->buttons[1][4] = make_button(17, 0, 0, 0xffff, f->transport, 1);
    f->buttons[1][4].num_nav_cmds = 2;
    f->buttons[1][5] = make_button(30, 10, 80, 102, &f->action, 0);
    f->buttons[1][5].lower_button_id_ref = 99;
    f->buttons[1][0].lower_button_id_ref = 17;
    f->pages[1].num_bogs = 6;
    for (unsigned i = 4; i < 6; i++) f->bogs[1][i].default_valid_button_id_ref = f->buttons[1][i].id;
    enter_page(f, 1, 8);
}
static void add_background_return(struct fixture *f)
{
    /* Same structure as the House disc, deliberately different page/GPR IDs. */
    f->register_back[0] = (MOBJ_CMD){.insn = {.grp = INSN_GROUP_SET, .sub_grp = SET_SET,
        .set_opt = INSN_MOVE, .op_cnt = 2, .imm_op2 = 1}, .dst = 17, .src = 100};
    f->register_back[1] = f->register_back[0];
    f->register_back[1].dst = 29;
    f->register_back[1].src = 41;
    f->register_back[2] = set_page(29, 17);
    f->register_back[2].insn.imm_op1 = f->register_back[2].insn.imm_op2 = 0;
    f->buttons[1][3].nav_cmds = f->register_back;
    f->buttons[1][3].num_nav_cmds = 4;
    f->buttons[1][1] = make_button(7, 80, 90, 83, &f->nop, 0);
    f->buttons[1][1].upper_button_id_ref = f->buttons[1][1].lower_button_id_ref = 8;
    f->buttons[1][1].left_button_id_ref = f->buttons[1][1].right_button_id_ref = 8;
    f->bogs[1][1].default_valid_button_id_ref = 7;
    f->bogs[1][2].default_valid_button_id_ref = 0xffff; /* no individual toolbar copies */
    f->pages[1].default_selected_button_id_ref = 0xffff;
    enter_page(f, 1, 8);
}
static void test_background_return(void)
{
    struct fixture f;
    GC_NAV_CMDS cmds = {0};
    init(&f); add_background_return(&f);
    assert(_authored_return_page(&f.buttons[1][3]) == 41);
    assert(find(&f, NULL) == &f.buttons[1][3]); /* Back does not need a pointer */
    assert(_mouse_move(&f.gc, 260, 110, &cmds) == 1 && f.regs[PSR_SELECTED_BUTTON_ID] == 8);
    assert(_user_input(&f.gc, BD_VK_MOUSE_ACTIVATE, &cmds) == 1 && cmds.nav_cmds == f.register_back);
    assert(f.gc.pointer_target_button == 101);
    enter_page(&f, 0, 100); cmds = (GC_NAV_CMDS){0};
    assert(_complete_pointer_route(&f.gc, &cmds) && cmds.nav_cmds == &f.action);
    init(&f); add_background_return(&f); cmds = (GC_NAV_CMDS){0};
    assert(_mouse_move(&f.gc, 110, 110, &cmds) == 1);
    assert(_user_input(&f.gc, BD_VK_MOUSE_ACTIVATE, &cmds) == 1);
    enter_page(&f, 0, 100); cmds = (GC_NAV_CMDS){0};
    assert(_complete_pointer_route(&f.gc, &cmds) && !cmds.num_nav_cmds); /* same icon closes */
    init(&f); add_background_return(&f); f.regs[PSR_SELECTED_BUTTON_ID] = 7;
    assert(find(&f, NULL) == &f.buttons[1][3]); /* previously trapped background focus */
    init(&f); add_background_return(&f); cmds = (GC_NAV_CMDS){0};
    assert(_mouse_move(&f.gc, 200, 110, &cmds) == 1); /* gap between the parent icons */
    assert(!_user_input(&f.gc, BD_VK_MOUSE_ACTIVATE, &cmds) && !cmds.num_nav_cmds);
    init(&f); add_background_return(&f); cmds = (GC_NAV_CMDS){0};
    f.buttons[0][1].x_pos = f.buttons[0][0].x_pos;
    assert(_mouse_move(&f.gc, 110, 110, &cmds) == 1);
    assert(!_user_input(&f.gc, BD_VK_MOUSE_ACTIVATE, &cmds)); /* ambiguous hit */
    init(&f); add_background_return(&f); f.buttons[1][1].x_pos = 150;
    assert(!find(&f, NULL)); /* one contained parent button center is insufficient */
    init(&f); add_background_return(&f); cmds = (GC_NAV_CMDS){0};
    f.buttons[0][0].y_pos = f.buttons[0][1].y_pos = 125; /* edges extend below the cropped backdrop */
    assert(_mouse_move(&f.gc, 260, 150, &cmds) == 1 && f.regs[PSR_SELECTED_BUTTON_ID] == 8);
    assert(_user_input(&f.gc, BD_VK_MOUSE_ACTIVATE, &cmds) == 1 && f.gc.pointer_target_button == 101);
    init(&f); add_background_return(&f); f.buttons[0][0].y_pos = f.buttons[0][1].y_pos = 165;
    assert(!find(&f, NULL)); /* a slight edge overlap without matching centers is not a toolbar */
    init(&f); add_background_return(&f); f.gc.bog_data[3].enabled_button = 0xffff;
    assert(!find(&f, NULL));
    init(&f); add_background_return(&f); f.register_back[2].src = 0x80000000 | 30;
    assert(!find(&f, NULL)); /* unknown GPR, never read speculative VM state */
    init(&f); add_background_return(&f); f.register_back[0].insn.imm_op2 = 0;
    assert(!find(&f, NULL)); /* nonconstant assignment */
    init(&f); add_background_return(&f); f.register_back[0].dst = 18;
    assert(!find(&f, NULL)); /* missing button operand and unrelated write */
    init(&f); add_background_return(&f); f.register_back[3] = f.register_back[0];
    assert(!find(&f, NULL)); /* only NOP may follow the terminating menu command */
    init(&f); add_background_return(&f); f.register_back[0].insn.grp = INSN_GROUP_BRANCH;
    f.register_back[0].insn.sub_grp = BRANCH_JUMP; f.register_back[0].insn.branch_opt = INSN_JUMP_TITLE;
    assert(!find(&f, NULL));
}
int main(void)
{
    test_background_return();
    struct fixture f;
    GC_NAV_CMDS cmds = {0};
    init(&f);
    MOBJ_CMD nop[2] = {0};
    BD_IG_BUTTON no_action = make_button(7, 0, 792, 83, nop, 0);
    assert(_button_has_no_action(&no_action));
    no_action.num_nav_cmds = 2;
    assert(_button_has_no_action(&no_action));
    nop[1] = f.action;
    assert(!_button_has_no_action(&no_action));
    no_action.nav_cmds = NULL;
    assert(!_button_has_no_action(&no_action));
    assert(!_button_has_no_action(NULL));
    assert(_inert_pointer_button(&f.buttons[1][2]));
    assert(find(&f, &f.buttons[1][2]) == &f.buttons[1][3]);
    assert(_mouse_move(&f.gc, 260, 110, &cmds) == 1 && f.regs[PSR_SELECTED_BUTTON_ID] == 8);
    assert(_user_input(&f.gc, BD_VK_MOUSE_ACTIVATE, &cmds) == 1 && cmds.nav_cmds == &f.back);
    assert(f.gc.pointer_route_pending && f.gc.pointer_target_button == 101);
    enter_page(&f, 0, 100);
    assert(!_complete_pointer_route(&f.gc, &cmds)); /* existing VM commands are never overwritten */
    cmds = (GC_NAV_CMDS){0};
    assert(_complete_pointer_route(&f.gc, &cmds) && cmds.nav_cmds == &f.action && f.regs[PSR_SELECTED_BUTTON_ID] == 101);
    assert(!f.gc.pointer_route_pending);

    init(&f); cmds = (GC_NAV_CMDS){0};
    assert(_mouse_move(&f.gc, 110, 110, &cmds) == 1);
    assert(_user_input(&f.gc, BD_VK_MOUSE_ACTIVATE, &cmds) == 1);
    enter_page(&f, 0, 100); cmds = (GC_NAV_CMDS){0};
    assert(_complete_pointer_route(&f.gc, &cmds) && !cmds.num_nav_cmds); /* same entry closes */

    init(&f); add_transport(&f); cmds = (GC_NAV_CMDS){0};
    assert(find(&f, NULL) == &f.buttons[1][4]);
    assert(_user_input(&f.gc, BD_VK_MENU_BACK, &cmds) == 1 && cmds.nav_cmds == f.transport);
    enter_page(&f, 1, 30); cmds = (GC_NAV_CMDS){0};
    assert(_complete_pointer_route(&f.gc, &cmds) && cmds.nav_cmds == &f.back);
    enter_page(&f, 0, 100); cmds = (GC_NAV_CMDS){0};
    assert(_complete_pointer_route(&f.gc, &cmds) && !cmds.num_nav_cmds);

    init(&f); add_transport(&f);
    f.transport[0].insn.set_opt = INSN_SET_STREAM;
    assert(!find(&f, NULL)); /* never cross an audio/playlist/register side effect */
    init(&f); f.gc.bog_data[3].enabled_button = 0xffff;
    assert(!find(&f, NULL));
    init(&f); f.buttons[1][3].selected_start_object_id_ref = 55;
    assert(!find(&f, NULL)); /* a visible next-page auto is not Back */
    init(&f); f.back.insn.imm_op2 = 0;
    assert(!find(&f, NULL)); /* unknown register target */
    init(&f); f.buttons[1][4] = make_button(199, 0, 0, 0xffff, &f.other_back, 1);
    f.pages[1].num_bogs = 5; f.bogs[1][4].default_valid_button_id_ref = 199;
    f.buttons[1][0].right_button_id_ref = 199; enter_page(&f, 1, 8);
    assert(!find(&f, NULL)); /* conflicting parents are not guessed */
    init(&f); f.regs[PSR_SELECTED_BUTTON_ID] = 61;
    assert(find(&f, NULL) == &f.buttons[1][3]); /* recover an old trapped selection */
    init(&f); cmds = (GC_NAV_CMDS){0};
    assert(_user_input(&f.gc, BD_VK_MENU_BACK, &cmds) == 1);
    now += 180001; cmds = (GC_NAV_CMDS){0};
    assert(!_complete_pointer_route(&f.gc, &cmds) && !f.gc.pointer_route_pending);
    init(&f); cmds = (GC_NAV_CMDS){0};
    assert(_user_input(&f.gc, BD_VK_MENU_BACK, &cmds) == 1);
    enter_page(&f, 1, 8); cmds = (GC_NAV_CMDS){0};
    _user_input(&f.gc, BD_VK_LEFT, &cmds);
    assert(!f.gc.pointer_route_pending);
    f.gc.pointer_route_pending = 1;
    assert(_mouse_move(&f.gc, 1900, 1000, &cmds) == 0 && !f.gc.pointer_route_pending);
    init(&f); f.gc.button_effect_running = 1;
    assert(_user_input(&f.gc, BD_VK_MENU_BACK, &cmds) == -1);
    puts("PASS: authored parent graph / inert & merged-background pointer selection / constant GPR return / sibling activation / toggle close / multi-step VM transport / disabled & visible auto rejection / ambiguous & unknown register rejection / timeout / newer-input cancellation / effect deferral");
    return 0;
}
