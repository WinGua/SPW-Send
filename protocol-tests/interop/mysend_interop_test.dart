import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mysend/models/device_info.dart';
import 'package:mysend/models/sync_mapping.dart';
import 'package:mysend/services/local_index_service.dart';
import 'package:mysend/services/sync_engine.dart';
import 'package:mysend/services/transfer/local_transfer_server.dart';
import 'package:mysend/services/transfer/part_store.dart';
import 'package:mysend/services/transfer/peer_client.dart';
import 'package:mysend/services/transfer/transfer_identity.dart';
import 'package:mysend/storage/config_repository.dart';

// 从 MySend 项目目录执行，需先在插件 protocol-tests 中生成运行时 classpath。
void main() {
  test('MySend ↔ SPW-Send：双向发起、续传、并发竞争、镜像进度与内容校验', () async {
    const classpathFile = String.fromEnvironment('SPW_CLASSPATH');
    if (classpathFile.isEmpty) throw StateError('请通过 --dart-define=SPW_CLASSPATH=... 指定 runtime-classpath.txt');
    final classpath = (await File(classpathFile).readAsString()).trim();
    final root = await Directory.systemTemp.createTemp('mysend_spw_interop_');
    final pc = Directory('${root.path}/pc');
    final mobile = Directory('${root.path}/mobile');
    await mobile.create();
    final process = await Process.start('java', ['-cp', classpath,
      'com.wingua.spwsend.sync.InteropHarnessKt', root.path]);
    final errors = StringBuffer();
    process.stderr.transform(utf8.decoder).listen(errors.write);
    final output = StreamIterator(process.stdout.transform(utf8.decoder).transform(const LineSplitter()));
    Future<Map<String, dynamic>> next() async {
      while (await output.moveNext().timeout(const Duration(seconds: 35))) {
        final line = output.current;
        if (line.startsWith('{')) return jsonDecode(line) as Map<String, dynamic>;
      }
      throw StateError('插件进程已退出：$errors');
    }
    LocalTransferServer? server;
    PeerClient? client;
    try {
      final hello = await next();
      final identity = await TransferIdentity.generate();
      final config = ConfigRepository();
      config.config.deviceId = 'android-peer';
      config.config.deviceName = 'MySend Android';
      final mapping = SyncMapping(id: 'music', name: 'music', sourcePath: mobile.path, destPath: mobile.path);
      config.config.mappings.add(mapping);
      var reverseToken = '';
      server = LocalTransferServer(identity: identity, preferredPort: 0, config: config,
        isSender: false, session: 'dart', onPairRequest: (_) async => true, onPeerPaired: (_, token) { reverseToken = token; }, onSyncLog: (_) async {}, onDisconnect: (_) {});
      await server.start();
      final peer = PeerDevice(id: hello['id'] as String, name: 'SPW test', form: 'desktop', session: 'plugin',
        address: '127.0.0.1', port: hello['port'] as int, fingerprint: hello['fingerprint'] as String, lastSeen: DateTime.now());
      PeerClient makeClient([String token = '']) => PeerClient(identity: identity, peer: peer, token: token,
        session: 'dart', deviceId: config.config.deviceId, deviceName: config.config.deviceName,
        role: 'android', form: 'phone', localPort: server!.port);
      final pairing = makeClient();
      final outcome = await pairing.pair(tokenToOffer: server.issueTokenFor(peer.id));
      pairing.close();
      client = makeClient(outcome.token);
      Future<void> seed(String path, int length, int value) => File(path).writeAsBytes(List.filled(length, value)).then((_) {});
      Future<void> equal(String path) async => expect(
        sha256.convert(await File('${pc.path}/$path').readAsBytes()),
        sha256.convert(await File('${mobile.path}/$path').readAsBytes()));
      Future<SyncResult> runDart() async {
        final plan = SyncEngine.computeBidirectionalPlan(mapping: mapping,
          local: await LocalIndexService.scan(mobile.path), remote: {for (final e in await client!.fetchIndex(mapping.name)) e.relativePath: e});
        return SyncEngine.runBidirectional(mapping: mapping, client: client!, localServer: server!,
          plan: plan, localRoot: mobile.path, onProgress: (_, __, ___, ____) {});
      }
      Future<Map<String, dynamic>> command(String op) async {
        process.stdin.writeln(jsonEncode({'op': op, 'id': config.config.deviceId, 'port': server!.port, 'fingerprint': identity.fingerprint}));
        await process.stdin.flush();
        return next();
      }

      await seed('${pc.path}/pc.bin', 600003, 41);
      await seed('${mobile.path}/mobile.bin', 700009, 19);
      await seed('${pc.path}/empty-pc.bin', 0, 0);
      await seed('${mobile.path}/empty-mobile.bin', 0, 0);
      final first = await runDart();
      expect(first.failed, 0); expect(first.pushed, 2); expect(first.pulled, 2);
      for (final path in ['pc.bin', 'mobile.bin', 'empty-pc.bin', 'empty-mobile.bin']) { await equal(path); }
      final pcProgress = await command('snapshot');
      expect(pcProgress['finished'], isTrue); expect(pcProgress['error'], isNull);
      expect(pcProgress['sent'], 2); expect(pcProgress['received'], 2);

      await seed('${pc.path}/resume-pc.bin', 1000003, 52);
      await seed('${mobile.path}/resume-mobile.bin', 1100017, 62);
      Future<void> partial(String source, String target, String path, int length) async {
        final file = File('$source/$path'); final stat = await file.stat();
        await PartStore.writeMeta(target, path, PartMeta(path: path, size: stat.size, mtime: stat.modified.millisecondsSinceEpoch));
        await PartStore.partFile(target, path).writeAsBytes((await file.readAsBytes()).sublist(0, length));
      }
      await partial(pc.path, mobile.path, 'resume-pc.bin', 123456);
      await partial(mobile.path, pc.path, 'resume-mobile.bin', 234567);
      for (var i = 0; i < 24; i++) { await seed('${pc.path}/small-$i.bin', 8193, i); }
      final second = await command('sync');
      expect(second['ok'], isTrue, reason: '$second');
      expect(second['failed'], 0); expect(second['sent'], 25); expect(second['received'], 1);
      await equal('resume-pc.bin'); await equal('resume-mobile.bin');
      for (var i = 0; i < 24; i++) { await equal('small-$i.bin'); }
      expect(server.remoteSync.value!.finished, isTrue);
      expect(server.remoteSync.value!.error, isNull);
      expect(server.remoteSync.value!.sentCount, 1);
      expect(server.remoteSync.value!.receivedCount, 25);

      await seed('${pc.path}/race-pc.bin', 8*1024*1024, 7);
      await seed('${mobile.path}/race-mobile.bin', 8*1024*1024, 9);
      final racePlan = SyncEngine.computeBidirectionalPlan(mapping: mapping,
        local: await LocalIndexService.scan(mobile.path), remote: {for (final e in await client.fetchIndex(mapping.name)) e.relativePath: e});
      final native = command('sync');
      final dart = () async {
        try {
          final r = await SyncEngine.runBidirectional(mapping: mapping, client: client!, localServer: server!, plan: racePlan,
            localRoot: mobile.path, onProgress: (_, __, ___, ____) {});
          return {'ok': true, 'sent': r.pushed, 'received': r.pulled, 'failed': r.failed};
        } catch (e) { return {'ok': false, 'error': '$e'}; }
      }();
      final results = await Future.wait([native, dart]);
      expect(results.where((r) => r['ok'] == true), isNotEmpty, reason: '$results');
      final transferred = results.where((r) => r['ok'] == true).fold<int>(0, (sum, r) => sum + (r['sent'] as int) + (r['received'] as int));
      expect(transferred, 2, reason: '不得重复传输：$results');
      await equal('race-pc.bin'); await equal('race-mobile.bin');
      expect(await Directory('${pc.path}/.mysend-part').exists(), isFalse);
      expect(await Directory('${mobile.path}/.mysend-part').exists(), isFalse);
      // Cancel a real plugin-initiated task from the receiving phone as soon as it opens.
      await seed('${pc.path}/cancel-pc.bin', 4 * 1024 * 1024, 17);
      final cancellation = Completer<void>();
      String? cancellingTask;
      void cancelIncoming() {
        final state = server!.remoteSync.value;
        if (state == null || state.finished || state.task == cancellingTask) return;
        cancellingTask = state.task;
        final control = makeClient(reverseToken.isEmpty ? outcome.token : reverseToken);
        () async {
          try {
            final request = control.requestCancellation(state.task);
            await server!.cancelTask(state.task);
            await request;
            cancellation.complete();
          } catch (e, stack) {
            // Local connection shutdown may finish the remote task before its cancel RPC arrives.
            // Only the documented already-ended response is allowed; completion is asserted below.
            if (e is TransferException && e.status == 409 && e.code == 'BUSY') cancellation.complete();
            else cancellation.completeError(e, stack);
          }
          finally { control.close(); }
        }();
      }
      server.remoteSync.addListener(cancelIncoming);
      final cancelledTask = command('sync');
      try {
        await cancellation.future.timeout(const Duration(seconds: 2));
        final stopped = await cancelledTask.timeout(const Duration(seconds: 2));
        expect(stopped['cancelled'] == true || '${stopped['error']}'.contains('已取消'), isTrue, reason: '$stopped');
        final snapshot = await command('outboundSnapshot');
        expect(snapshot['finished'], isTrue);
        expect(snapshot['error'], contains('已取消'));
        expect(server.sessions.busy, isFalse);
      } finally { server.remoteSync.removeListener(cancelIncoming); }
      // ignore: avoid_print
      await seed('${pc.path}/00-io-fail.bin', 128000, 1);
      for (var i=0; i<12; i++) { await seed('${pc.path}/healthy-$i.bin', 128003+i, i+1); }
      final localError = await command('syncLocalFailure');
      expect(localError['ok'], isTrue, reason: '$localError');
      expect(localError['cancelled'], isFalse);
      expect(localError['failed'], 1);
      for (var i=0; i<12; i++) { await equal('healthy-$i.bin'); }

      await seed('${pc.path}/plugin-cancel.bin', 16*1024*1024, 13);
      final ended = Completer<void>();
      void onPluginCancel() {
        final state=server!.remoteSync.value;
        if (state?.finished == true && state?.error == '同步已取消' && !ended.isCompleted) ended.complete();
      }
      server.remoteSync.addListener(onPluginCancel);
      try {
        final pluginCancel = await command('syncCancel');
        expect(pluginCancel['cancelled'] == true || '${pluginCancel['error']}'.contains('已取消'), isTrue, reason: '$pluginCancel');
        onPluginCancel();
        await ended.future.timeout(const Duration(seconds: 3));
        expect(server.remoteSync.value!.error, '同步已取消');
      } finally { server.remoteSync.removeListener(onPluginCancel); }

      print('INTEROP PASSED: Dart initiates; Kotlin initiates; resume; 4-file concurrency; session race; mirrored progress; phone cancellation; plugin cancellation; local I/O continues; SHA-256');
    } finally {
      client?.close(); await server?.stop();
      process.stdin.writeln('{"op":"stop"}'); await process.stdin.close();
      try { await process.exitCode.timeout(const Duration(seconds: 10)); } catch (_) { process.kill(); }
      await output.cancel();
      await root.delete(recursive: true);
    }
  }, timeout: const Timeout(Duration(minutes: 3)));
}
