// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MaxHUD",
    platforms: [
        .macOS(.v13)
    ],
    products: [
        .executable(name: "MaxHUD", targets: ["MaxHUD"])
    ],
    dependencies: [],
    targets: [
        .executableTarget(
            name: "MaxHUD",
            dependencies: [],
            path: "Sources/MaxHUD"
        ),
        .testTarget(
            name: "MaxHUDTests",
            dependencies: ["MaxHUD"],
            path: "Tests/MaxHUDTests"
        )
    ]
)
