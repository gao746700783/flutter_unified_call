# Contribution and publishing guide

This guide defines the repository's commit and release conventions. Routine
development commits do not change published package versions.

## Git commits

Use this title format:

```text
ready for <target-version> <type>(<scope>): <中文描述>
```

The `ready for` prefix identifies the intended release line; it does not mean
that the commit changes package metadata or is itself a release. Use the next
planned version as `<target-version>` (for example, `0.1.2`). Keep the remainder
in Conventional Commits form and make each commit serve one purpose.

Examples:

```text
ready for 0.1.2 fix(android): 修复锁屏来电唤醒
ready for 0.2.0 feat(web): 支持配置证书校验
ready for 0.1.2 docs(api): 完善初始化参数说明
ready for 0.1.2 refactor(call): 简化状态转换
ready for 0.1.2 test(android): 增加通知恢复覆盖
ready for 0.1.2 build(android): 更新构建配置
```

Choose `fix` for a compatibility-preserving bug fix, `feat` for a
backward-compatible capability, `docs`, `build`, `refactor`, or `test` for the
corresponding work, and a scope that names the affected module. Mark a breaking
change with `!`, for example `feat(api)!: 调整入会接口参数`, and include a
`BREAKING CHANGE:` section in the commit body describing affected callers and
the migration path. Prefer retaining a released public API and deprecating it
when practical.

Before committing, inspect the staged diff and exclude real credentials, local
debug settings, generated machine-specific files, and unfinished experiments.
Keep each commit focused on one independent purpose.

## Versioning and changelog

Use semantic versioning for the Flutter package. Select the highest impact in a
release batch: compatibility fix increments PATCH, backward-compatible feature
increments MINOR, and a breaking change increments MAJOR. Apply the same policy
while the package is in `0.x`; the first breaking release is `1.0.0`.

Keep user-visible changes under the upcoming version in the root
`CHANGELOG.md`. A routine fix or feature commit does not bump versions. Prepare
a release in a separate commit titled:

```text
ready for <new-version> chore(release): 发布 Flutter 插件 <new-version>
```

That release commit updates the version in `pubspec.yaml` and
`ios/flutter_unified_call.podspec`, the current-version text in `README.md`, and
the corresponding `CHANGELOG.md` section. Keep these package version values in
sync. `example/pubspec.yaml` is the example application's own version and is
not the plugin package version. The Android library version is supplied by the
Flutter plugin build; this repository does not use `PUBLISH_VERSION_NAME`,
`PUBLISH_VERSION_CODE`, or a root `gradle.properties` for plugin releases.

## Release checks

Before publishing, review the current gaps in
[`IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md) and the relevant platform
guides. Run the checks appropriate to the release and inspect the staged release
diff.

- Verify package metadata and archive contents with `flutter pub publish --dry-run`.
- Check that the Android plugin's declared `minSdk` remains 24 in
  `android/build.gradle.kts`; do not copy an SDK 21 requirement from another
  project or introduce APIs above the minimum without runtime guards.
- Review Android consumer/shrinker rules if adding reflection or manifest-based
  entry points. There is currently no `proguard-rules.pro` in this repository.
- Validate Android, iOS, Web, and example builds/tests that are relevant to the
  change. For call flows, cover direct and conference calls plus network loss
  and reconnection in a host integration app; this plugin does not implement
  signaling or media transport itself.
- Check host compatibility, permissions, lifecycle behavior, and known OS/OEM
  limitations in the platform guides. Do not treat unit tests as proof of
  physical-device, push-delivery, Telecom, or browser behavior.
- Publish only after the release commit and required checks are complete, using
  `flutter pub publish` from the package root.

Do not update version numbers during daily development. Version changes belong
only to the dedicated release commit.
