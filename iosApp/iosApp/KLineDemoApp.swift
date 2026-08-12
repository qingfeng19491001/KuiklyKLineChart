import SwiftUI

@main
struct KLineDemoApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

struct ContentView: View {
    var body: some View {
        VStack {
            Text("Kuikly KLine Demo")
                .font(.headline)
                .padding()
            KLineChartViewRepresentable()
                .edgesIgnoringSafeArea(.all)
        }
    }
}

#Preview {
    ContentView()
}
