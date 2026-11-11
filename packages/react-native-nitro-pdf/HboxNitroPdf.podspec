require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "HboxNitroPdf"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = "https://github.com/hbox/rn-pdf-kit"
  s.license      = package["license"]
  s.authors      = { "hbox" => "dev@hbox.dev" }
  s.platforms    = { :ios => "15.1" }
  s.source       = { :git => "https://github.com/hbox/rn-pdf-kit.git", :tag => "#{s.version}" }
  s.source_files = [
    "ios/**/*.{swift,h,m,mm}",
    "cpp/**/*.{hpp,cpp}",
  ]
  s.swift_version = "5.9"

  # Adds all Nitrogen-generated sources plus the NitroModules dependency.
  load 'nitrogen/generated/ios/HboxNitroPdf+autolinking.rb'
  add_nitrogen_files(s)

  s.dependency "React-Core"
  s.dependency "React-jsi"
  s.dependency "React-callinvoker"
  install_modules_dependencies(s)
end
