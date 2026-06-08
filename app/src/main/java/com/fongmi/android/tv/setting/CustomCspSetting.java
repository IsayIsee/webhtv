package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.gson.ExtAdapter;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class CustomCspSetting {

    private static final String DIR = "TV/CustomCsp";
    private static final String REGISTRY = "registry.json";
    private static final String PREFIX = "__custom_csp_";
    private static final int MAX_INSERT_INDEX = 9;
    private static final String KIND_WEB_HOME = "webHome";
    private static final String KIND_CSP = "csp";
    private static final String KIND_LIVE = "live";

    public static Registry load() {
        String text = Path.read(registryFile());
        return objectFrom(text);
    }

    public static Registry objectFrom(String text) {
        try {
            return parse(text);
        } catch (Exception e) {
            return new Registry();
        }
    }

    public static Registry parse(String text) throws Exception {
        if (TextUtils.isEmpty(text)) return new Registry();
        JsonElement element = JsonParser.parseString(text);
        if (element.isJsonNull()) return new Registry();
        if (element.isJsonArray()) return new Registry().items(itemsFrom(element.getAsJsonArray())).normalize();
        if (!element.isJsonObject()) throw new IllegalArgumentException("Invalid custom CSP JSON");
        JsonObject object = element.getAsJsonObject();
        if (object.has("items")) {
            Registry registry = App.gson().fromJson(object, Registry.class);
            if (registry != null && object.has("home") && !object.has("homeKey")) registry.setHomeKey(object.get("home").getAsString());
            if (registry != null && object.get("items").isJsonArray()) registry.setItems(itemsFrom(object.getAsJsonArray("items")));
            return registry == null ? new Registry() : registry.normalize();
        }
        Registry registry = new Registry();
        if (object.has("enabled")) registry.setEnabled(object.get("enabled").getAsBoolean());
        if (object.has("insertIndex")) registry.setInsertIndex(object.get("insertIndex").getAsInt());
        if (object.has("homeKey")) registry.setHomeKey(object.get("homeKey").getAsString());
        else if (object.has("home")) registry.setHomeKey(object.get("home").getAsString());
        registry.items(itemsFrom(object));
        return registry.normalize();
    }

    private static List<Item> itemsFrom(JsonObject object) {
        List<Item> items = new ArrayList<>();
        if (object.has("sites") && object.get("sites").isJsonArray()) items.addAll(itemsFrom(object.getAsJsonArray("sites"), false));
        if (object.has("lives") && object.get("lives").isJsonArray()) items.addAll(itemsFrom(object.getAsJsonArray("lives"), true));
        if (items.isEmpty()) items.add(itemFrom(object, isLiveObject(object)));
        return items;
    }

    private static List<Item> itemsFrom(JsonArray array) {
        return itemsFrom(array, null);
    }

    private static List<Item> itemsFrom(JsonArray array, Boolean live) {
        List<Item> items = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            items.add(itemFrom(object, live == null ? isLiveObject(object) : live));
        }
        return items;
    }

    private static Item itemFrom(JsonObject object, boolean live) {
        Item item = App.gson().fromJson(object, Item.class);
        if (live) {
            item.setKind(KIND_LIVE);
            if (!object.has("live")) item.setLive(object.deepCopy());
        } else if (!object.has("site")) {
            item.setSite(object.deepCopy());
        }
        return item.normalize();
    }

    private static boolean isLiveObject(JsonObject object) {
        if (object.has("kind") && object.get("kind").isJsonPrimitive()) return KIND_LIVE.equals(object.get("kind").getAsString());
        if (object.has("live") && object.get("live").isJsonObject()) return true;
        if (object.has("site") || object.has("key")) return false;
        return object.has("url") || object.has("groups") || object.has("epg");
    }

    public static void save(Registry registry) {
        Path.write(registryFile(), App.gson().toJson(registry.normalize()).getBytes(StandardCharsets.UTF_8));
    }

    public static File dir() {
        File dir = Path.root(DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File file(String id, String name) {
        return new File(new File(dir(), id), name);
    }

    public static String localUrl(String id, String name) {
        return "file://" + DIR + "/" + id + "/" + name;
    }

    public static Result inject(List<Site> sites) {
        Registry registry = load();
        if (!registry.isEnabled()) return Result.empty();
        Server.get().start();
        List<Site> items = registry.sites();
        if (items.isEmpty()) return Result.empty();
        for (Site site : items) sites.remove(site);
        int index = Math.max(0, Math.min(registry.getInsertIndex(), sites.size()));
        sites.addAll(index, items);
        Site home = items.stream().filter(site -> site.getKey().equals(registry.getHomeKey())).findFirst().orElse(new Site());
        return new Result(home);
    }

    public static void inject(List<Live> lives, String spider) {
        Registry registry = load();
        if (!registry.isEnabled()) return;
        List<Live> items = registry.lives(spider);
        if (items.isEmpty()) return;
        for (Live live : items) lives.remove(live);
        int index = Math.max(0, Math.min(registry.getInsertIndex(), lives.size()));
        lives.addAll(index, items);
    }

    public static boolean hasLives() {
        Registry registry = load();
        return registry.isEnabled() && registry.getItems().stream().anyMatch(item -> item.isEnabled() && item.isLive() && item.isValid());
    }

    public static int countEnabled() {
        return count().active();
    }

    public static int countItems() {
        return load().getItems().size();
    }

    public static Count count() {
        Registry registry = load();
        int enabled = 0;
        int active = 0;
        for (Item item : registry.getItems()) {
            if (!item.isEnabled()) continue;
            enabled++;
            if (registry.isEnabled() && item.isValid()) active++;
        }
        return new Count(active, enabled);
    }

    public static Item createDefaultItem() {
        Item item = new Item();
        item.setId("local_" + System.currentTimeMillis() + "_" + Long.toHexString(System.nanoTime()));
        item.setKey(PREFIX + item.getId());
        item.setWebHome(true);
        item.setType(3);
        item.setApi("");
        return item;
    }

    public static Item createDefaultLiveItem() {
        Item item = new Item();
        item.setId("live_" + System.currentTimeMillis() + "_" + Long.toHexString(System.nanoTime()));
        item.setKind(KIND_LIVE);
        item.setType(0);
        item.setPlayerType(2);
        item.setUa("okhttp");
        return item;
    }

    public record Count(int active, int enabled) {
    }

    private static File registryFile() {
        return new File(dir(), REGISTRY);
    }

    public record Result(Site home) {

        public static Result empty() {
            return new Result(new Site());
        }
    }

    public static class Registry {

        @SerializedName("enabled")
        private Boolean enabled;
        @SerializedName("insertIndex")
        private Integer insertIndex;
        @SerializedName("homeKey")
        private String homeKey;
        @SerializedName("items")
        private List<Item> items;

        public Registry normalize() {
            if (items == null) items = new ArrayList<>();
            items.removeIf(Objects::isNull);
            Set<String> ids = new HashSet<>();
            List<Item> unique = new ArrayList<>();
            for (Item item : items) {
                item.normalize();
                if (!ids.add(item.getId())) continue;
                unique.add(item);
            }
            items = unique;
            if (homeKey == null) homeKey = "";
            return this;
        }

        public boolean isEnabled() {
            return enabled == null || enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getInsertIndex() {
            return insertIndex == null ? 0 : Math.max(0, Math.min(MAX_INSERT_INDEX, insertIndex));
        }

        public void setInsertIndex(int insertIndex) {
            this.insertIndex = Math.max(0, Math.min(MAX_INSERT_INDEX, insertIndex));
        }

        public String getHomeKey() {
            return TextUtils.isEmpty(homeKey) ? "" : homeKey;
        }

        public void setHomeKey(String homeKey) {
            this.homeKey = homeKey;
        }

        public List<Item> getItems() {
            return items == null ? Collections.emptyList() : items;
        }

        public void setItems(List<Item> items) {
            this.items = items;
        }

        public Registry items(List<Item> items) {
            setItems(items);
            return this;
        }

        public Item addDefault() {
            Item item = createDefaultItem();
            if (items == null) items = new ArrayList<>();
            items.add(item);
            return item;
        }

        public List<Site> sites() {
            return getItems().stream().filter(Item::isEnabled).filter(item -> !item.isLive()).filter(Item::isValid).map(Item::site).filter(site -> !site.isEmpty()).toList();
        }

        public List<Live> lives(String spider) {
            return getItems().stream().filter(Item::isEnabled).filter(Item::isLive).filter(Item::isValid).map(item -> item.live(spider)).filter(live -> !live.isEmpty()).toList();
        }
    }

    public static class Item {

        @SerializedName("id")
        private String id;
        @SerializedName("key")
        private String key;
        @SerializedName("name")
        private String name;
        @SerializedName("enabled")
        private Boolean enabled;
        @SerializedName("kind")
        private String kind;
        @SerializedName("webHome")
        private Boolean webHome;
        @SerializedName("type")
        private Integer type;
        @SerializedName("playerType")
        private Integer playerType;
        @SerializedName("api")
        private String api;
        @SerializedName("ext")
        @JsonAdapter(ExtAdapter.class)
        private String ext;
        @SerializedName("jar")
        private String jar;
        @SerializedName("homePage")
        private String homePage;
        @SerializedName("click")
        private String click;
        @SerializedName("playUrl")
        private String playUrl;
        @SerializedName("url")
        private String url;
        @SerializedName("logo")
        private String logo;
        @SerializedName("epg")
        private String epg;
        @SerializedName("ua")
        private String ua;
        @SerializedName("origin")
        private String origin;
        @SerializedName("referer")
        private String referer;
        @SerializedName("timeZone")
        private String timeZone;
        @SerializedName("timeout")
        private Integer timeout;
        @SerializedName("hide")
        private Integer hide;
        @SerializedName("searchable")
        private Integer searchable;
        @SerializedName("changeable")
        private Integer changeable;
        @SerializedName("quickSearch")
        private Integer quickSearch;
        @SerializedName("site")
        private JsonObject site;
        @SerializedName("live")
        private JsonObject live;

        public Item normalize() {
            normalizeKind();
            if (TextUtils.isEmpty(id)) id = Util.md5(getKey() + getName() + getApi() + getHomePage() + getUrl());
            if (!isLive() && TextUtils.isEmpty(key)) {
                String siteKey = getSiteString("key");
                key = TextUtils.isEmpty(siteKey) ? PREFIX + id : siteKey;
            }
            return this;
        }

        private void normalizeKind() {
            if (!TextUtils.isEmpty(kind)) return;
            if (live != null) kind = KIND_LIVE;
            else kind = inferWebHome() ? KIND_WEB_HOME : KIND_CSP;
        }

        public boolean isEnabled() {
            return enabled == null || enabled;
        }

        public boolean isWebHome() {
            if (isLive()) return false;
            return KIND_WEB_HOME.equals(kind) || (webHome != null && webHome) || isWebHomeByFields();
        }

        private boolean isWebHomeByFields() {
            return webHome == null ? inferWebHome() : webHome;
        }

        private boolean inferWebHome() {
            String apiValue = !TextUtils.isEmpty(api) ? api.trim() : getSiteString("api");
            String homeValue = !TextUtils.isEmpty(homePage) ? homePage.trim() : getSiteString("homePage");
            if (TextUtils.isEmpty(homeValue)) homeValue = getSiteString("webHome");
            return apiValue.isEmpty() && !homeValue.isEmpty();
        }

        public boolean isLive() {
            normalizeKind();
            return KIND_LIVE.equals(kind);
        }

        public String getKind() {
            normalizeKind();
            return isLive() ? KIND_LIVE : isWebHome() ? KIND_WEB_HOME : KIND_CSP;
        }

        public boolean isValid() {
            if (isLive()) return !getName().isEmpty() && (!getUrl().isEmpty() || hasLiveGroups());
            return isWebHome() ? !getHomePage().isEmpty() : !getApi().isEmpty();
        }

        public String getDefaultName() {
            return isLive() ? "直播" : isWebHome() ? "WebHome" : "通用 CSP";
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public void setWebHome(boolean webHome) {
            this.webHome = webHome;
            setKind(webHome ? KIND_WEB_HOME : KIND_CSP);
        }

        public void setKind(String kind) {
            boolean wasLive = isLive();
            this.kind = KIND_LIVE.equals(kind) ? KIND_LIVE : KIND_WEB_HOME.equals(kind) ? KIND_WEB_HOME : KIND_CSP;
            this.webHome = KIND_WEB_HOME.equals(this.kind);
            if (KIND_LIVE.equals(this.kind) && live == null) live = new JsonObject();
            if (KIND_LIVE.equals(this.kind)) {
                key = null;
                if (!wasLive) {
                    if (type == null) type = 0;
                    if (playerType == null) playerType = 2;
                    if (TextUtils.isEmpty(ua)) ua = "okhttp";
                }
                homePage = null;
                playUrl = null;
                hide = null;
                searchable = null;
                changeable = null;
                quickSearch = null;
                site = null;
            } else {
                live = null;
                if (wasLive) type = null;
                url = null;
                logo = null;
                epg = null;
                ua = null;
                playerType = null;
                origin = null;
                referer = null;
                timeZone = null;
                timeout = null;
            }
        }

        public String getId() {
            normalize();
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public void setKey(String key) {
            this.key = key;
            putSite("key", key);
        }

        public void setName(String name) {
            this.name = name;
            if (isLive()) putLive("name", name);
            else putSite("name", name);
        }

        public void setType(Integer type) {
            this.type = type;
            if (isLive()) putLive("type", type);
            else putSite("type", type);
        }

        public Integer getType() {
            return type == null ? isLive() ? getLiveInt("type", 0) : getSiteInt("type", 3) : type;
        }

        public void setPlayerType(Integer playerType) {
            this.playerType = playerType;
            if (playerType == null) removeLive("playerType");
            else putLive("playerType", playerType);
        }

        public Integer getPlayerType() {
            return playerType == null ? getLiveInt("playerType", null) : playerType;
        }

        public Integer getHide() {
            return hide == null ? getSiteInt("hide", 0) : hide;
        }

        public Integer getSearchable() {
            return searchable == null ? getSiteInt("searchable", isWebHome() ? 0 : 1) : searchable;
        }

        public Integer getChangeable() {
            return changeable == null ? getSiteInt("changeable", 1) : changeable;
        }

        public Integer getQuickSearch() {
            return quickSearch == null ? getSiteInt("quickSearch", isWebHome() ? 0 : 1) : quickSearch;
        }

        public void setApi(String api) {
            this.api = api;
            if (isLive()) putLive("api", api);
            else putSite("api", api);
        }

        public void setExt(String ext) {
            this.ext = ext;
            if (isLive()) putLive("ext", ext);
            else putSite("ext", ext);
        }

        public void setJar(String jar) {
            this.jar = jar;
            if (isLive()) putLive("jar", jar);
            else putSite("jar", jar);
        }

        public void setHomePage(String homePage) {
            this.homePage = homePage;
            putSite("homePage", homePage);
        }

        public void setClick(String click) {
            this.click = click;
            if (isLive()) putLive("click", click);
            else putSite("click", click);
        }

        public void setPlayUrl(String playUrl) {
            this.playUrl = playUrl;
            putSite("playUrl", playUrl);
        }

        public void setUrl(String url) {
            this.url = url;
            putLive("url", url);
        }

        public void setLogo(String logo) {
            this.logo = logo;
            putLive("logo", logo);
        }

        public void setEpg(String epg) {
            this.epg = epg;
            putLive("epg", epg);
        }

        public void setUa(String ua) {
            this.ua = ua;
            putLive("ua", ua);
        }

        public void setOrigin(String origin) {
            this.origin = origin;
            putLive("origin", origin);
        }

        public void setReferer(String referer) {
            this.referer = referer;
            putLive("referer", referer);
        }

        public void setTimeZone(String timeZone) {
            this.timeZone = timeZone;
            putLive("timeZone", timeZone);
        }

        public void setTimeout(Integer timeout) {
            this.timeout = timeout;
            if (timeout == null) removeLive("timeout");
            else putLive("timeout", timeout);
        }

        public void setHide(Integer hide) {
            this.hide = hide;
            putSite("hide", hide);
        }

        public void setSearchable(Integer searchable) {
            this.searchable = searchable;
            putSite("searchable", searchable);
        }

        public void setChangeable(Integer changeable) {
            this.changeable = changeable;
            putSite("changeable", changeable);
        }

        public void setQuickSearch(Integer quickSearch) {
            this.quickSearch = quickSearch;
            putSite("quickSearch", quickSearch);
        }

        public void setSite(JsonObject site) {
            this.site = site;
        }

        public void setLive(JsonObject live) {
            this.live = live;
        }

        public String getKey() {
            return !TextUtils.isEmpty(key) ? key.trim() : getSiteString("key");
        }

        public String getName() {
            String value = !TextUtils.isEmpty(name) ? name.trim() : isLive() ? getLiveString("name") : getSiteString("name");
            return TextUtils.isEmpty(value) ? getKey() : value;
        }

        public String getApi() {
            return !TextUtils.isEmpty(api) ? api.trim() : isLive() ? getLiveString("api") : getSiteString("api");
        }

        public String getExt() {
            return !TextUtils.isEmpty(ext) ? ext.trim() : isLive() ? getLiveString("ext") : getSiteString("ext");
        }

        public String getJar() {
            return !TextUtils.isEmpty(jar) ? jar.trim() : isLive() ? getLiveString("jar") : getSiteString("jar");
        }

        public String getHomePage() {
            String value = !TextUtils.isEmpty(homePage) ? homePage.trim() : getSiteString("homePage");
            return TextUtils.isEmpty(value) ? getSiteString("webHome") : value;
        }

        public String getClick() {
            return !TextUtils.isEmpty(click) ? click.trim() : isLive() ? getLiveString("click") : getSiteString("click");
        }

        public String getPlayUrl() {
            return !TextUtils.isEmpty(playUrl) ? playUrl.trim() : getSiteString("playUrl");
        }

        public String getUrl() {
            return !TextUtils.isEmpty(url) ? url.trim() : getLiveString("url");
        }

        public String getLogo() {
            return !TextUtils.isEmpty(logo) ? logo.trim() : getLiveString("logo");
        }

        public String getEpg() {
            return !TextUtils.isEmpty(epg) ? epg.trim() : getLiveString("epg");
        }

        public String getUa() {
            return !TextUtils.isEmpty(ua) ? ua.trim() : getLiveString("ua");
        }

        public String getOrigin() {
            return !TextUtils.isEmpty(origin) ? origin.trim() : getLiveString("origin");
        }

        public String getReferer() {
            return !TextUtils.isEmpty(referer) ? referer.trim() : getLiveString("referer");
        }

        public String getTimeZone() {
            return !TextUtils.isEmpty(timeZone) ? timeZone.trim() : getLiveString("timeZone");
        }

        public Integer getTimeout() {
            return timeout == null ? getLiveInt("timeout", null) : timeout;
        }

        public Site site() {
            if (isLive()) return new Site();
            normalize();
            if (site != null) return siteFromJson();
            Site site = new Site();
            boolean webHomeOnly = isWebHome();
            site.setKey(getKey());
            site.setName(getName());
            site.setType(getType());
            site.setApi(webHomeOnly ? "" : UrlUtil.convert(getApi()));
            site.setExt(webHomeOnly ? "" : UrlUtil.convert(getExt()));
            site.setJar(webHomeOnly ? "" : getJar());
            site.setHomePage(UrlUtil.convert(getHomePage()));
            site.setClick(webHomeOnly ? "" : getClick());
            site.setPlayUrl(webHomeOnly ? "" : getPlayUrl());
            site.setHide(getHide());
            site.setSearchable(getSearchable());
            site.setChangeable(getChangeable());
            site.setQuickSearch(getQuickSearch());
            site.setStyle(Style.rect());
            return site;
        }

        public Live live(String spider) {
            normalize();
            return Live.objectFrom(liveObject(), spider);
        }

        private JsonObject liveObject() {
            JsonObject object = live == null ? new JsonObject() : live.deepCopy();
            if (!TextUtils.isEmpty(name)) object.addProperty("name", name.trim());
            else if (!object.has("name")) object.addProperty("name", getName());
            if (type != null) object.addProperty("type", type);
            if (playerType != null) object.addProperty("playerType", playerType);
            if (!TextUtils.isEmpty(url)) object.addProperty("url", url.trim());
            if (!TextUtils.isEmpty(api)) object.addProperty("api", api.trim());
            if (!TextUtils.isEmpty(ext)) object.addProperty("ext", ext.trim());
            if (!TextUtils.isEmpty(jar)) object.addProperty("jar", jar.trim());
            if (!TextUtils.isEmpty(click)) object.addProperty("click", click.trim());
            if (!TextUtils.isEmpty(logo)) object.addProperty("logo", logo.trim());
            if (!TextUtils.isEmpty(epg)) object.addProperty("epg", epg.trim());
            if (!TextUtils.isEmpty(ua)) object.addProperty("ua", ua.trim());
            if (!TextUtils.isEmpty(origin)) object.addProperty("origin", origin.trim());
            if (!TextUtils.isEmpty(referer)) object.addProperty("referer", referer.trim());
            if (!TextUtils.isEmpty(timeZone)) object.addProperty("timeZone", timeZone.trim());
            if (timeout != null) object.addProperty("timeout", timeout);
            return object;
        }

        private Site siteFromJson() {
            JsonObject object = site.deepCopy();
            if (!TextUtils.isEmpty(key)) object.addProperty("key", key.trim());
            else if (!object.has("key")) object.addProperty("key", getKey());
            if (!TextUtils.isEmpty(name)) object.addProperty("name", name.trim());
            if (!TextUtils.isEmpty(api)) object.addProperty("api", api.trim());
            if (!TextUtils.isEmpty(ext)) object.addProperty("ext", ext.trim());
            if (!TextUtils.isEmpty(jar)) object.addProperty("jar", jar.trim());
            if (!TextUtils.isEmpty(homePage)) object.addProperty("homePage", homePage.trim());
            if (!TextUtils.isEmpty(click)) object.addProperty("click", click.trim());
            if (!TextUtils.isEmpty(playUrl)) object.addProperty("playUrl", playUrl.trim());
            if (type != null) object.addProperty("type", type);
            if (hide != null) object.addProperty("hide", hide);
            if (searchable != null) object.addProperty("searchable", searchable);
            if (changeable != null) object.addProperty("changeable", changeable);
            if (quickSearch != null) object.addProperty("quickSearch", quickSearch);
            if (isWebHome()) {
                object.addProperty("api", "");
                object.addProperty("ext", "");
                object.addProperty("jar", "");
            }
            Site result = Site.objectFrom(object, getJar());
            boolean webHomeOnly = isWebHome() || result.getApi().isEmpty() && !result.getHomePage().isEmpty();
            if (webHomeOnly && searchable == null && !object.has("searchable")) result.setSearchable(0);
            if (webHomeOnly && quickSearch == null && !object.has("quickSearch")) result.setQuickSearch(0);
            return result;
        }

        private String getSiteString(String key) {
            return getString(site, key);
        }

        private String getLiveString(String key) {
            return getString(live, key);
        }

        private String getString(JsonObject object, String key) {
            if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) return "";
            return object.get(key).getAsString().trim();
        }

        private int getSiteInt(String key, int fallback) {
            try {
                if (site == null || !site.has(key) || !site.get(key).isJsonPrimitive()) return fallback;
                return site.get(key).getAsInt();
            } catch (Exception e) {
                return fallback;
            }
        }

        private Integer getLiveInt(String key, Integer fallback) {
            try {
                if (live == null || !live.has(key) || !live.get(key).isJsonPrimitive()) return fallback;
                return live.get(key).getAsInt();
            } catch (Exception e) {
                return fallback;
            }
        }

        private boolean hasLiveGroups() {
            return live != null && live.has("groups") && live.get("groups").isJsonArray() && !live.getAsJsonArray("groups").isEmpty();
        }

        private void putSite(String key, String value) {
            if (site == null) return;
            if (TextUtils.isEmpty(value) && site.has(key) && !site.get(key).isJsonPrimitive()) return;
            site.addProperty(key, value);
        }

        private void putSite(String key, Integer value) {
            if (site != null && value != null) site.addProperty(key, value);
        }

        private void putLive(String key, String value) {
            if (live == null) return;
            if (TextUtils.isEmpty(value)) {
                removeLive(key);
                return;
            }
            live.addProperty(key, value);
        }

        private void putLive(String key, Integer value) {
            if (live != null && value != null) live.addProperty(key, value);
        }

        private void removeLive(String key) {
            if (live != null) live.remove(key);
        }
    }
}
