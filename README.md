本分支根据自己的使用习惯修改了部分操作，没有能力修改任何功能，如有相关请求请到 [fish2018/webhtv](https://github.com/fish2018/webhtv) 提交

## 自定义内容
- **清除缓存改为长按，避免意外清除**
- **新版本检查时如果是手工执行的在没有可更新版本时提示：当前是最新版本**
- **版本检查修改为配合 [IsayIsee/TVBoxOS-Build](https://github.com/IsayIsee/TVBoxOS-Build) 项目的编译结果**
- ~~长按版本打开 github 加速设置~~ # webhtv 已经支持壳级别的代理设置，此功能显得多余已取消
- 2026-06-24 **非手工检查更新时如果发生版本检查错误不提示，防止打开应用就弹出错误提示**（升级文件在 Github，国内网络环境可能导致失败）


## 记录
- 部署远程托管 WebHTV Remote Vercel Edge Relay 注意内容
  - 问题
    - 按照 README 部署完成后，访问 /api/server/capabilities 报 404
  - 解决方案
    - 在 Vercel 上打开对应项目 -> CDN -> Routing Rules -> Create Route
    - 匹配选择 Regex 值：/api/(.*)
    - 方法选择 Rewrite 值：/api