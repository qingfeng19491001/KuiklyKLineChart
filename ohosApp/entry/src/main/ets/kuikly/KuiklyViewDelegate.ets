import {
  IKuiklyViewDelegate,
  KRRenderModuleExportCreator,
  KRRenderViewExportCreator
} from '@kuikly-open/render';
import { KRBridgeModule } from './modules/KRBridgeModule';
import { KRKLineChart } from './components/KRKLineChart';

export class KuiklyViewDelegate extends IKuiklyViewDelegate {
  // 必须走 V1 注册：V2 注册的自定义 view 没有影子节点，frame 等基础属性需自行在 ArkTS 侧应用，
  // 否则视图拿不到布局尺寸而撑满整页（实测 frame=0 220.857 358.857 439.714 被丢弃，Canvas 仍为 788.57vp）。
  getCustomRenderViewCreatorRegisterMap(): Map<string, KRRenderViewExportCreator> {
    const map: Map<string, KRRenderViewExportCreator> = new Map();
    map.set(KRKLineChart.VIEW_NAME, () => new KRKLineChart());
    return map;
  }

  getCustomRenderViewCreatorRegisterMapV2(): Map<string, KRRenderViewExportCreator> {
    return new Map();
  }

  getCustomRenderModuleCreatorRegisterMap(): Map<string, KRRenderModuleExportCreator> {
    const map: Map<string, KRRenderModuleExportCreator> = new Map();
    map.set(KRBridgeModule.MODULE_NAME, () => new KRBridgeModule());
    return map;
  }
}
