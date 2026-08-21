import { ErrorReason, KRRecord, Kuikly, KuiklyRenderBaseView } from '@kuikly-open/render';
import router from '@ohos.router';
import { KuiklyViewDelegate } from '../kuikly/KuiklyViewDelegate';
import globalNativeManager from '../kuikly/MyNativeManager';

@Entry
@Component
struct Index {
  kuiklyViewDelegate = new KuiklyViewDelegate();
  pageName?: string;
  pageData?: KRRecord;
  @State exception?: string = "";

  aboutToAppear() {
    AppStorage.setOrCreate<UIContext>("context", this.getUIContext());

    const params = router.getParams() as Record<string, Object>;
    const routePageName = params?.pageName as string;
    const wantPageName = AppStorage.get<string>('kline_page_name');
    this.pageName = routePageName ?? wantPageName;

    const basePageData = (params?.pageData as KRRecord) ?? {};
    // 冷启动直连页面时补齐 statusBarHeight（正常路由会由框架注入）
    if (this.pageName && this.pageName !== 'router') {
      const merged: Record<string, Object> = {};
      const keys = Object.keys(basePageData);
      for (let i = 0; i < keys.length; i++) {
        merged[keys[i]] = basePageData[keys[i]];
      }
      const statusBarHeight = AppStorage.get<number>('status_bar_height');
      if (statusBarHeight !== undefined && statusBarHeight > 0) {
        merged['statusBarHeight'] = statusBarHeight;
      }
      this.pageData = merged as KRRecord;
    } else {
      this.pageData = basePageData;
    }
  }

  build() {
    Stack() {
      Kuikly({
        pagerName: this.pageName ?? 'router',
        pagerData: this.pageData ?? {},
        delegate: this.kuiklyViewDelegate,
        nativeManager: globalNativeManager,
        onRenderException: (exception: Error, reason: ErrorReason) => {
          this.exception = `${exception.name}:\n${exception.message}`;
        },
      })
      if (this.exception) {
        Text(this.exception)
      }
    }
    .width('100%')
    .height('100%')
    .expandSafeArea([SafeAreaType.KEYBOARD])
  }

  onPageShow(): void {
    this.kuiklyViewDelegate.pageDidAppear();
  }

  onPageHide(): void {
    this.kuiklyViewDelegate.pageDidDisappear();
  }
}
