import { KRAny, KuiklyRenderBaseView, KuiklyRenderCallback } from '@kuikly-open/render';
import { ComponentContent } from '@kit.ArkUI';

@Observed
export class KRKLineChart extends KuiklyRenderBaseView {
  static readonly VIEW_NAME = 'KRKLineChart';
  symbol: string = '{}';
  period: string = '{}';
  mode: string = 'full';
  priceStyle: string = 'candle';
  bars: string = '[]';
  signals: string = '[]';
  config: string = '{}';
  onSignalClick: KuiklyRenderCallback | null = null;

  createArkUIView(): ComponentContent<KuiklyRenderBaseView> {
    return new ComponentContent<KuiklyRenderBaseView>(
      this.getUIContext() as UIContext,
      wrapBuilder<[KuiklyRenderBaseView]>(createKLineChart),
      this
    );
  }

  setProp(propKey: string, propValue: KRAny | KuiklyRenderCallback): boolean {
    switch (propKey) {
      case 'symbol': this.symbol = propValue as string; return true;
      case 'period': this.period = propValue as string; return true;
      case 'mode': this.mode = propValue as string; return true;
      case 'priceStyle': this.priceStyle = propValue as string; return true;
      case 'bars': this.bars = propValue as string; return true;
      case 'signals': this.signals = propValue as string; return true;
      case 'config': this.config = propValue as string; return true;
      case 'onSignalClick': this.onSignalClick = propValue as KuiklyRenderCallback; return true;
      default: return super.setProp(propKey, propValue);
    }
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): void {
    if (method === 'resetViewport' || method === 'scrollToLatest' || method === 'clearCrosshair') return;
    super.call(method, params, callback);
  }
}

@Component
struct KRKLineChartComponent {
  @ObjectLink renderView: KRKLineChart;

  build() {
    Stack() {
      Canvas()
        .width('100%')
        .height('100%')
        .backgroundColor('#FFFFFF')
      if (this.renderView.bars === '[]') {
        Text('暂无行情数据').fontSize(12).fontColor('#9CA3AF')
      }
    }
    .size({ width: '100%', height: '100%' })
    .alignContent(Alignment.Center)
  }
}

@Builder
function createKLineChart(view: KuiklyRenderBaseView) {
  KRKLineChartComponent({ renderView: view as KRKLineChart });
}
