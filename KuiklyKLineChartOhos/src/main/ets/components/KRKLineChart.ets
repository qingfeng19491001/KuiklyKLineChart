import { KRAny, KuiklyRenderBaseView, KuiklyRenderCallback } from '@kuikly-open/render';
import { ComponentContent, LengthMetricsUnit } from '@kit.ArkUI';
import Napi from 'libentry.so';

interface KLinePoint {
  x: number;
  y: number;
}

interface KLineCommand {
  type: string;
  x?: number;
  y?: number;
  x1?: number;
  y1?: number;
  x2?: number;
  y2?: number;
  left?: number;
  top?: number;
  right?: number;
  bottom?: number;
  open?: number;
  high?: number;
  low?: number;
  close?: number;
  bodyWidth?: number;
  wickWidth?: number;
  radius?: number;
  color?: string;
  strokeColor?: string;
  strokeWidth?: number;
  width?: number;
  text?: string;
  textSize?: number;
  dash?: number[];
  points?: KLinePoint[];
}

interface KLineNativeEvent {
  name: string;
  payload: KRAny;
}

@Observed
export class KRKLineChart extends KuiklyRenderBaseView {
  static readonly VIEW_NAME = 'KRKLineChart';
  bridgeHandle: number = -1;
  bars: string = '[]';
  eventCallbacks: Map<string, KuiklyRenderCallback> = new Map();
  requestRender: (() => void) | null = null;
  private disposed: boolean = false;

  /**
   * 惰性创建 Kotlin 侧桥接。
   *
   * V1（影子节点）注册路径下，属性会先于 createArkUIView() 下发，
   * 若此时 bridgeHandle 仍为 -1，symbol/bars/config 等属性会被直接丢弃（表现为"暂无行情数据"）。
   */
  private ensureBridge(): number {
    if (this.bridgeHandle < 0 && !this.disposed) {
      this.bridgeHandle = Napi.createKLineBridge();
      // 绘制内核以物理像素为坐标系（对齐 Android/iOS），密度用于把窗格标题位置换算回 vp
      Napi.setKLineProp(this.bridgeHandle, 'density', String(vp2px(1)));
    }
    return this.bridgeHandle;
  }

  createArkUIView(): ComponentContent<KuiklyRenderBaseView> {
    this.ensureBridge();
    return new ComponentContent<KuiklyRenderBaseView>(
      this.getUIContext() as UIContext,
      wrapBuilder<[KuiklyRenderBaseView]>(createKLineChart),
      this
    );
  }

  setProp(propKey: string, propValue: KRAny | KuiklyRenderCallback): boolean {
    if (propKey.startsWith('on')) {
      this.eventCallbacks.set(propKey, propValue as KuiklyRenderCallback);
      return true;
    }
    if (this.ensureBridge() < 0) return false;
    const value = propValue as string;
    const handled = Napi.setKLineProp(this.bridgeHandle, propKey, value);
    if (!handled) return super.setProp(propKey, propValue);
    if (propKey === 'bars') this.bars = value;
    if (this.requestRender !== null) this.requestRender();
    return true;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): void {
    if (this.ensureBridge() < 0) return;
    const result = Napi.callKLine(this.bridgeHandle, method, JSON.stringify(params));
    if (callback !== null) callback(JSON.parse(result) as KRAny);
    if (this.requestRender !== null) this.requestRender();
  }

  dispatchEvents(): void {
    if (this.bridgeHandle < 0) return;
    const events = JSON.parse(Napi.pollKLineEvents(this.bridgeHandle)) as KLineNativeEvent[];
    events.forEach((event: KLineNativeEvent) => {
      const callback = this.eventCallbacks.get(event.name);
      if (callback !== undefined) callback(event.payload);
    });
  }

  disposeBridge(): void {
    this.disposed = true;
    if (this.bridgeHandle < 0) return;
    Napi.disposeKLineBridge(this.bridgeHandle);
    this.bridgeHandle = -1;
    this.requestRender = null;
    this.eventCallbacks.clear();
  }
}

@Component
struct KRKLineChartComponent {
  @ObjectLink renderView: KRKLineChart;
  // 画布按物理像素取值，与 Android/iOS 送入绘制内核的坐标系一致；
  // 若沿用默认 vp 单位，内核会以 1/density 的分辨率排布，蜡烛数量与线宽都会偏大。
  private canvasContext: CanvasRenderingContext2D =
    new CanvasRenderingContext2D(new RenderingContextSettings(true), LengthMetricsUnit.PX);
  /** 物理像素 */
  private canvasWidth: number = 0;
  /** 物理像素 */
  private canvasHeight: number = 0;
  private downX: number = 0;
  private downY: number = 0;
  private lastX: number = 0;
  private lastY: number = 0;
  private initialPinchDistance: number = 0;
  private pinchActive: boolean = false;
  private horizontalGesture: boolean = false;
  private longPressTimer: number = -1;
  private longPressTriggered: boolean = false;

  aboutToAppear(): void {
    this.renderView.requestRender = () => this.redraw();
  }

  build() {
    Stack() {
      Canvas(this.canvasContext)
        .width('100%')
        .height('100%')
        .backgroundColor('#00000000')
        .onReady(() => this.resizeAndDraw())
        .onAreaChange((_oldValue: Area, newValue: Area) => {
          this.canvasWidth = vp2px(Number(newValue.width));
          this.canvasHeight = vp2px(Number(newValue.height));
          this.resizeAndDraw();
        })
        .onTouch((event: TouchEvent) => { this.handleTouch(event); })
      if (this.renderView.bars === '[]') {
        Text('暂无行情数据').fontSize(12).fontColor('#9CA3AF')
      }
    }
    .size({ width: '100%', height: '100%' })
    .alignContent(Alignment.Center)
  }

  private resizeAndDraw(): void {
    if (this.renderView.bridgeHandle < 0 || this.canvasWidth <= 0 || this.canvasHeight <= 0) return;
    Napi.resizeKLine(this.renderView.bridgeHandle, Math.round(this.canvasWidth), Math.round(this.canvasHeight));
    this.redraw();
  }

  private redraw(): void {
    if (this.renderView.bridgeHandle < 0 || this.canvasWidth <= 0 || this.canvasHeight <= 0) return;
    const commands = JSON.parse(Napi.renderKLineCommands(this.renderView.bridgeHandle)) as KLineCommand[];
    const context = this.canvasContext;
    context.clearRect(0, 0, this.canvasWidth, this.canvasHeight);
    commands.forEach((command: KLineCommand) => this.drawCommand(command));
    this.renderView.dispatchEvents();
  }

  private drawCommand(command: KLineCommand): void {
    const context = this.canvasContext;
    const color = command.color ?? '#000000';
    switch (command.type) {
      case 'line':
        context.beginPath();
        context.strokeStyle = color;
        context.lineWidth = command.width ?? 1;
        context.setLineDash(command.dash ?? []);
        context.moveTo(command.x1 ?? 0, command.y1 ?? 0);
        context.lineTo(command.x2 ?? 0, command.y2 ?? 0);
        context.stroke();
        context.setLineDash([]);
        break;
      case 'rect': {
        const left = command.left ?? 0;
        const top = command.top ?? 0;
        const width = (command.right ?? left) - left;
        const height = (command.bottom ?? top) - top;
        context.fillStyle = color;
        context.fillRect(left, top, width, height);
        if ((command.strokeWidth ?? 0) > 0) {
          context.strokeStyle = command.strokeColor ?? '#000000';
          context.lineWidth = command.strokeWidth ?? 1;
          context.strokeRect(left, top, width, height);
        }
        break;
      }
      case 'polyline': {
        const points = command.points ?? [];
        if (points.length === 0) break;
        context.beginPath();
        context.strokeStyle = color;
        context.lineWidth = command.width ?? 1;
        context.setLineDash(command.dash ?? []);
        context.moveTo(points[0].x, points[0].y);
        for (let index = 1; index < points.length; index++) {
          context.lineTo(points[index].x, points[index].y);
        }
        context.stroke();
        context.setLineDash([]);
        break;
      }
      case 'circle':
        context.beginPath();
        context.fillStyle = color;
        context.arc(command.x ?? 0, command.y ?? 0, command.radius ?? 0, 0, Math.PI * 2);
        context.fill();
        break;
      case 'candle': {
        const x = command.x ?? 0;
        const open = command.open ?? 0;
        const close = command.close ?? 0;
        const bodyWidth = command.bodyWidth ?? 1;
        context.strokeStyle = color;
        context.lineWidth = command.wickWidth ?? 1;
        context.beginPath();
        context.moveTo(x, command.high ?? 0);
        context.lineTo(x, command.low ?? 0);
        context.stroke();
        context.fillStyle = color;
        context.fillRect(x - Math.max(1, bodyWidth) / 2, Math.min(open, close), Math.max(1, bodyWidth), Math.max(1, Math.abs(close - open)));
        break;
      }
      case 'text':
        context.fillStyle = color;
        context.font = `${command.textSize ?? 12}px sans-serif`;
        context.textAlign = 'left';
        context.textBaseline = 'top';
        context.fillText(command.text ?? '', command.left ?? 0, command.top ?? 0);
        break;
    }
  }

  /** 手势阈值由 Host 的 KLineGestureArbitrator 按像素判定；此处只采集 raw touch。 */
  private pointer(kind: string, x: number, y: number, scale: number, count: number): void {
    Napi.pointerKLine(this.renderView.bridgeHandle, kind, vp2px(x), vp2px(y), scale, count);
  }

  private claimPointerMove(dxVp: number, dyVp: number, count: number): string {
    return Napi.callKLine(
      this.renderView.bridgeHandle,
      'claimPointerMove',
      `${vp2px(dxVp)},${vp2px(dyVp)},${count}`
    );
  }

  private resetGestureClaim(): void {
    Napi.callKLine(this.renderView.bridgeHandle, 'resetGestureClaim', '');
  }

  private handleTouch(event: TouchEvent): void {
    const points = event.touches.length > 0 ? event.touches : event.changedTouches;
    if (points.length === 0) return;
    const center = touchCenter(points);
    const x = center.x;
    const y = center.y;
    switch (event.type) {
      case TouchType.Down:
        this.resetGestureClaim();
        if (points.length >= 2) {
          this.cancelLongPressTimer();
          this.pinchActive = true;
          this.horizontalGesture = true;
          this.initialPinchDistance = touchDistance(points[0], points[1]);
          this.claimPointerMove(0, 0, points.length);
          this.pointer('cancel', x, y, 1, points.length);
          this.pointer('secondaryDown', x, y, 1, points.length);
          event.stopPropagation();
          break;
        }
        this.downX = x;
        this.downY = y;
        this.lastX = x;
        this.lastY = y;
        this.horizontalGesture = false;
        this.longPressTriggered = false;
        this.pinchActive = false;
        this.initialPinchDistance = 0;
        this.pointer('down', x, y, 1, 1);
        this.startLongPressTimer(x, y);
        break;
      case TouchType.Move: {
        const dx = x - this.downX;
        const dy = y - this.downY;
        this.lastX = x;
        this.lastY = y;
        if (points.length >= 2 && !this.pinchActive) {
          this.cancelLongPressTimer();
          this.pinchActive = true;
          this.horizontalGesture = true;
          this.initialPinchDistance = touchDistance(points[0], points[1]);
          this.claimPointerMove(0, 0, points.length);
          this.pointer('cancel', x, y, 1, points.length);
          this.pointer('secondaryDown', x, y, 1, points.length);
        }
        const claim = this.claimPointerMove(dx, dy, points.length);
        if (claim === 'CHART') {
          this.horizontalGesture = true;
          this.cancelLongPressTimer();
        }
        if (claim === 'PARENT' && points.length === 1) {
          this.cancelLongPressTimer();
          this.pointer('cancel', x, y, 1, 1);
          return;
        }
        let scale = 1;
        if (points.length >= 2) {
          event.stopPropagation();
          const distance = touchDistance(points[0], points[1]);
          if (this.initialPinchDistance <= 0) this.initialPinchDistance = distance;
          scale = this.initialPinchDistance <= 0 ? 1 : distance / this.initialPinchDistance;
        } else if (this.horizontalGesture || this.longPressTriggered) {
          event.stopPropagation();
        }
        this.pointer('move', x, y, scale, points.length);
        break;
      }
      case TouchType.Up:
        this.cancelLongPressTimer();
        if (this.horizontalGesture || this.longPressTriggered || this.pinchActive) event.stopPropagation();
        this.pointer('up', x, y, 1, points.length);
        if (!this.horizontalGesture && !this.longPressTriggered && !this.pinchActive && Math.abs(x - this.downX) < 8 && Math.abs(y - this.downY) < 8) {
          this.pointer('tap', x, y, 1, 1);
        }
        this.pinchActive = false;
        this.initialPinchDistance = 0;
        this.resetGestureClaim();
        this.redraw();
        break;
      case TouchType.Cancel:
        this.cancelLongPressTimer();
        this.pointer('cancel', x, y, 1, points.length);
        this.pinchActive = false;
        this.initialPinchDistance = 0;
        this.resetGestureClaim();
        this.redraw();
        break;
    }
    if (event.type === TouchType.Move) this.redraw();
  }

  private startLongPressTimer(x: number, y: number): void {
    this.cancelLongPressTimer();
    this.longPressTimer = setTimeout(() => {
      this.longPressTriggered = true;
      this.pointer('longPress', this.lastX, this.lastY, 1, 1);
      this.redraw();
    }, 500);
  }

  private cancelLongPressTimer(): void {
    if (this.longPressTimer < 0) return;
    clearTimeout(this.longPressTimer);
    this.longPressTimer = -1;
  }

  aboutToDisappear(): void {
    this.cancelLongPressTimer();
    this.renderView.disposeBridge();
  }
}

@Builder
function createKLineChart(view: KuiklyRenderBaseView) {
  KRKLineChartComponent({ renderView: view as KRKLineChart });
}

function touchDistance(first: TouchObject, second: TouchObject): number {
  const dx = first.x - second.x;
  const dy = first.y - second.y;
  return Math.sqrt(dx * dx + dy * dy);
}

function touchCenter(points: TouchObject[]): KLinePoint {
  if (points.length < 2) return { x: points[0].x, y: points[0].y };
  return {
    x: (points[0].x + points[1].x) / 2,
    y: (points[0].y + points[1].y) / 2
  };
}
