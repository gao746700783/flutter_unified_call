#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint flutter_unified_call.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'flutter_unified_call'
  s.version          = '0.1.1'
  s.summary          = 'Engine-agnostic incoming call notifications and recovery.'
  s.description      = <<-DESC
Incoming call state, actionable notifications and durable event recovery without CallKit.
                       DESC
  s.homepage         = 'https://github.com/gao746700783/flutter_unified_call'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'gao746700783' => 'xhg746700783@gmail.com' }
  s.source           = { :path => '.' }
  s.source_files = 'flutter_unified_call/Sources/flutter_unified_call/**/*.{h,m,mm,swift}'
  s.dependency 'Flutter'
  s.platform = :ios, '13.0'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'

  # If your plugin requires a privacy manifest, for example if it uses any
  # required reason APIs, update the PrivacyInfo.xcprivacy file to describe your
  # plugin's privacy impact, and then uncomment this line. For more information,
  # see https://developer.apple.com/documentation/bundleresources/privacy_manifest_files
  s.resource_bundles = {'flutter_unified_call_privacy' => ['flutter_unified_call/Sources/flutter_unified_call/PrivacyInfo.xcprivacy']}
end
