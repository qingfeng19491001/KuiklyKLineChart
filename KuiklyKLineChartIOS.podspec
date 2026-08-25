Pod::Spec.new do |spec|
  spec.name = 'KuiklyKLineChartIOS'
  spec.version = ENV['kuiklyBizVersion'] || '0.1.0'
  spec.summary = 'KuiklyKLineChart iOS native expand View'
  spec.description = <<-DESC
    Native KRKLineChart UIView for Kuikly. Kotlin host logic ships via the app's KMP shared framework (KLCChartBridge).
  DESC
  spec.homepage = 'https://github.com/qingfeng19491001/KuiklyKLineChart'
  spec.license = { :type => 'MIT' }
  spec.author = { 'Kuikly' => 'kuikly@tencent.com' }
  spec.source = { :git => 'https://github.com/qingfeng19491001/KuiklyKLineChart.git', :tag => spec.version.to_s }
  spec.ios.deployment_target = '14.1'
  spec.requires_arc = true
  spec.frameworks = 'Foundation', 'UIKit'
  spec.source_files = 'KuiklyKLineChartIOS/*.{swift}'
  spec.static_framework = true
  spec.dependency 'OpenKuiklyIOSRender', '~> 2.15.0'
end
