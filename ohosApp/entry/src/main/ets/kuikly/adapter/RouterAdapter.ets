import { IKRRouterAdapter, KRRecord } from '@kuikly-open/render';
import router from '@ohos.router';
import common from '@ohos.app.ability.common';

export class RouterAdapter implements IKRRouterAdapter {
  openPage(context: common.UIAbilityContext, pageName: string, pageData: KRRecord): void {
    router.pushUrl({
      url: 'pages/Index',
      params: {
        pageName,
        pageData
      }
    })
  }

  closePage(context: common.UIAbilityContext): void {
    // 从入口 push 进来的页：正常返回
    if (Number(router.getLength()) > 1) {
      router.back()
      return
    }
    // 冷启动直达 Demo（栈底）时 back 无效，替换为 Router 入口页
    AppStorage.setOrCreate<string>('kline_page_name', 'router')
    router.replaceUrl({
      url: 'pages/Index',
      params: {
        pageName: 'router',
        pageData: {}
      }
    })
  }
}