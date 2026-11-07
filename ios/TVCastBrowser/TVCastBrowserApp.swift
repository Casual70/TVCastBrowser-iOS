import SwiftUI

@main
struct TVCastBrowserApp: App {
    var body: some Scene {
        WindowGroup {
            BrowserCastView()
                .preferredColorScheme(.dark)
        }
    }
}
