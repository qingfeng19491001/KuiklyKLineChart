#import "KRRouterHandler.h"
#import "KuiklyRenderViewController.h"

@implementation KRRouterHandler

+ (void)load {
    [KRRouterModule registerRouterHandler:[self new]];
}

- (void)openPageWithName:(NSString *)pageName pageData:(NSDictionary *)pageData controller:(UIViewController *)controller {
    KuiklyRenderViewController *renderViewController = [[KuiklyRenderViewController alloc] initWithPageName:pageName pageData:pageData];
    [controller.navigationController pushViewController:renderViewController animated:YES];
}

- (void)closePage:(UIViewController *)controller {
    UINavigationController *nav = controller.navigationController;
    if (nav.viewControllers.count > 1) {
        [nav popViewControllerAnimated:YES];
        return;
    }
    // 栈底页（如 -KLinePage FullChartDemo 冷启动）无法 pop，回到入口 Router 页
    KuiklyRenderViewController *router =
        [[KuiklyRenderViewController alloc] initWithPageName:@"router" pageData:@{}];
    [nav setViewControllers:@[router] animated:YES];
}

@end