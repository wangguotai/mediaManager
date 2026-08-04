#!/usr/bin/env node
/**
 * Produces the same JSON as `react-native config`, but rewrites
 * `project.ios.sourceDir` to point at the CocoaPods *installation_root*
 * (this `iosApp` directory).
 *
 * Why this is needed
 * ------------------
 * In React Native's `scripts/cocoapods/autolinking.rb`, every autolinked
 * pod is registered with:
 *
 *     relative_path = podspec_dir.relative_path_from(ios_project_root)
 *     pod name, :path => relative_path.to_path
 *
 * where `ios_project_root = config["project"]["ios"]["sourceDir"]` (from
 * `react-native config`). CocoaPods later resolves each `:path` against
 * `Pod::Config.instance.installation_root` (the directory containing the
 * Podfile — this `iosApp` dir).
 *
 * Normally the RN project's own ios folder IS the installation_root, so
 * the two bases match and everything works. But here the RN source lives
 * in a *different* repo (rn_test, reached via the `rn-sdk-test-link`
 * symlink), so `sourceDir` is `/Users/.../rn_test/ios` while
 * installation_root is `.../media-manager-ios-rn-sdk/frontend/iosApp`.
 * The relative `:path` values then resolve under the wrong tree and
 * `pod install` fails with e.g.:
 *     "No podspec found for `Expo` in `../node_modules/expo`".
 *
 * Fix: run `react-native config` for the rn_test project (so the CLI
 * finds its package.json, react-native.config.js and dependency graph),
 * then overwrite `project.ios.sourceDir` with this iosApp dir. After
 * this, `relative_path_from(ios_project_root)` and CocoaPods' own
 * `:path` resolution share the same base, so the cross-repo relative
 * paths (e.g. `../../rn-sdk-test-link/node_modules/expo`) resolve
 * correctly.
 *
 * The rn_test project itself is never modified.
 */
'use strict';

const path = require('path');

const RN_LINK = path.resolve(__dirname, '../../rn-sdk-test-link');
const INSTALLATION_ROOT = __dirname; // iosApp dir = CocoaPods installation_root

// Intercept stdout to capture the JSON that `react-native config` prints.
// `process.stdout` is read-only, so we monkeypatch its `write` method.
const chunks = [];
const realWrite = process.stdout.write.bind(process.stdout);
process.stdout.write = function (chunk, ...rest) {
  chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(String(chunk)));
  return true;
};

process.argv = ['', '', 'config'];
process.chdir(RN_LINK);

const cli = require(require.resolve('@react-native-community/cli', { paths: [RN_LINK] }));
cli.run().then(() => {
  process.stdout.write = realWrite;
  const raw = Buffer.concat(chunks).toString('utf8');
  const config = JSON.parse(raw);

  // Rewrite the ios project root so autolinking.rb computes `:path`
  // values relative to the CocoaPods installation_root (this iosApp dir)
  // instead of rn_test/ios.
  if (config.project && config.project.ios) {
    config.project.ios.sourceDir = INSTALLATION_ROOT;
  }

  realWrite(JSON.stringify(config));
}).catch((err) => {
  process.stdout.write = realWrite;
  console.error('rn-config-rewrite: react-native config failed:', err);
  process.exit(1);
});
