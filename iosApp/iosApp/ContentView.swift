import SwiftUI
import shared

struct ContentView: View {
	private var initialPage: String {
		let arguments = ProcessInfo.processInfo.arguments
		guard let index = arguments.firstIndex(of: "-KLinePage"), index + 1 < arguments.count else {
			return "router"
		}
		return arguments[index + 1]
	}

	var body: some View {
		KuiklyRenderViewPage(pageName: initialPage, data: [:]).ignoresSafeArea()
	}
}

struct ContentView_Previews: PreviewProvider {
	static var previews: some View {
		ContentView()
	}
}
