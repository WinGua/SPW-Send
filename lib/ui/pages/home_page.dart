import 'dart:async';

import 'package:fluent_ui/fluent_ui.dart';
import 'package:flutter/animation.dart' show Curves, Tween;
import 'package:flutter/foundation.dart' show ValueNotifier;
import 'package:flutter/widgets.dart'
    show TweenAnimationBuilder, ValueListenableBuilder;

import '../../app_scope.dart';
import '../../core/utils.dart';
import '../../models/device_info.dart';
import '../../models/sync_mapping.dart';
import '../../models/sync_plan.dart';
import '../widgets/glass.dart';

/// 状态页：本机信息、局域网设备、映射，以及由手机端发起的对比与同步。
class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  String? _busyId;
  PeerDevice? _peer;
  final Map<String, SyncPlan> _plans = {};
  final Map<String, bool> _comparing = {};
  final Map<String, bool> _syncing = {};

  @override
  void initState() {
    super.initState();
    scope.addListener(_refresh);
  }

  @override
  void dispose() {
    scope.removeListener(_refresh);
    super.dispose();
  }

  void _refresh() {
    if (!mounted) return;
    final devices = scope.discovery?.devices ?? const <PeerDevice>[];
    PeerDevice? connected;
    for (final d in devices) {
      if (d.connected) {
        connected = d;
        break;
      }
    }
    PeerDevice? current;
    final previous = _peer;
    if (previous != null) {
      for (final d in devices) {
        if (d.id == previous.id && d.connected) {
          current = d;
          break;
        }
      }
    }
    _peer = current ?? connected;
    if (_peer == null) _plans.clear();
    setState(() {});
  }

  Future<void> _connect(PeerDevice device, bool connected) async {
    if (_busyId != null) return;
    setState(() => _busyId = device.id);
    try {
      if (connected) {
        await scope.disconnectFrom(device);
        if (!mounted) return;
        setState(() {
          _peer = null;
          _plans.clear();
        });
      } else {
        final ok = await scope.connectTo(device);
        if (!mounted) return;
        if (ok) setState(() => _peer = device.copyWith(connected: true));
        await _showNotice(
          ok: ok,
          title: ok ? '已连接' : '连接失败',
          text: device.name,
        );
      }
    } finally {
      if (mounted) setState(() => _busyId = null);
    }
  }

  Future<void> _showNotice({
    required bool ok,
    required String title,
    required String text,
  }) {
    return showGlassDialog<void>(
      context: context,
      builder: (ctx) => _AutoCloseNotice(ok: ok, title: title, text: text),
    );
  }

  Future<void> _notice(String title, String text) =>
      _showNotice(ok: false, title: title, text: text);

  Future<void> _compare(SyncMapping mapping) async {
    final peer = _peer;
    if (peer == null) {
      await _notice('尚未连接', '请先连接一台电脑设备。');
      return;
    }
    if (_comparing[mapping.id] == true || _syncing[mapping.id] == true) return;

    setState(() => _comparing[mapping.id] = true);
    try {
      final plan = await scope.compareMapping(peer, mapping);
      if (!mounted) return;
      setState(() {
        _plans[mapping.id] = plan;
        _comparing[mapping.id] = false;
      });
      await _showCompareResult(mapping, plan);
    } catch (e) {
      if (!mounted) return;
      setState(() => _comparing[mapping.id] = false);
      await _notice('对比失败', '$e');
    }
  }

  Future<void> _showCompareResult(SyncMapping mapping, SyncPlan plan) async {
    final names =
        plan.newOnly.map((e) => e.relativePath.split('/').last).toList();
    final preview = names.take(3).join('\n');
    final more = names.length > 3 ? '\n…' : '';
    final accepted = await showGlassDialog<bool>(
      context: context,
      builder: (ctx) => GlassDialog(
        title: '对比完成 · ${mapping.name}',
        maxWidth: 380,
        content: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              plan.isEmpty
                  ? '两端文件一致，无需同步。'
                  : '新增 ${plan.newOnly.length} 个文件 · ${formatBytes(plan.totalBytes)}',
              style: const TextStyle(fontSize: 13, height: 1.5),
            ),
            if (!plan.isEmpty) ...[
              const SizedBox(height: 10),
              Text(
                '$preview$more',
                style: const TextStyle(
                  fontSize: 12,
                  height: 1.5,
                  color: Color(0xFF9A9A9A),
                ),
              ),
            ],
          ],
        ),
        actions: [
          Button(
            child: const Text('关闭'),
            onPressed: () => Navigator.pop(ctx, false),
          ),
          if (!plan.isEmpty)
            FilledButton(
              child: const Text('同步'),
              onPressed: () => Navigator.pop(ctx, true),
            ),
        ],
      ),
    );
    if (accepted == true && mounted) {
      await _sync(mapping, plan);
    }
  }

  Future<void> _sync(SyncMapping mapping, SyncPlan plan) async {
    final peer = _peer;
    if (peer == null || plan.isEmpty || _syncing[mapping.id] == true) return;

    setState(() => _syncing[mapping.id] = true);
    final progress = ValueNotifier<_SyncProgressState>(
      _SyncProgressState(
        total: plan.toSend.length,
        totalBytes: plan.totalBytes,
      ),
    );
    var cancelRequested = false;

    unawaited(
      showGlassDialog<void>(
        context: context,
        barrierDismissible: false,
        builder: (_) => _SyncProgressDialog(
          mappingName: mapping.name,
          progress: progress,
          onCancel: () {
            cancelRequested = true;
            progress.value = progress.value.copyWith(cancelling: true);
          },
        ),
      ).then((_) {}),
    );

    try {
      final outcome = await scope.syncMapping(
        device: peer,
        mapping: mapping,
        plan: plan,
        onProgress: (done, total, path) {
          progress.value = progress.value.copyWith(done: done, currentPath: path);
        },
        onBytes: (bytes) {
          progress.value = progress.value.copyWith(transferredBytes: bytes);
        },
        cancelled: () => cancelRequested,
      );
      progress.value = progress.value.copyWith(
        done: outcome.log.success + outcome.log.failed,
        completed: true,
        cancelled: outcome.cancelled,
        failed: outcome.log.failed,
        transferredBytes: outcome.log.bytes,
        currentPath: '',
      );
      if (mounted) setState(() => _plans.remove(mapping.id));
    } catch (e) {
      progress.value = progress.value.copyWith(completed: true, error: '$e');
    } finally {
      if (mounted) setState(() => _syncing[mapping.id] = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cfg = scope.config.config;
    final devices = scope.discovery?.devices ?? const <PeerDevice>[];
    final mappings = cfg.mappings;
    final serverPort = scope.server?.port ?? cfg.transferPort;
    final online = scope.server?.running == true;

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 24),
      children: [
        const Text(
          '状态',
          style: TextStyle(fontSize: 24, fontWeight: FontWeight.w600),
        ),
        const SizedBox(height: 4),
        const Text(
          'SPW-Send · 协议 v2',
          style: TextStyle(fontSize: 12, color: Color(0xFF8A8A8A)),
        ),
        const SizedBox(height: 16),
        GlassCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(FluentIcons.cell_phone,
                      size: 22, color: Color(0xFF4A9EFF)),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      cfg.deviceName,
                      style: const TextStyle(
                          fontSize: 16, fontWeight: FontWeight.w600),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                    decoration: BoxDecoration(
                      color: online
                          ? const Color(0x3342C36F)
                          : const Color(0x33FF6B6B),
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: Text(
                      online ? '在线' : '未启动',
                      style: TextStyle(
                        fontSize: 11,
                        color: online
                            ? const Color(0xFF7BE0A0)
                            : const Color(0xFFFF9B9B),
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              _infoRow('本机端口', '$serverPort'),
              _infoRow('映射数量', '${mappings.length}'),
              _infoRow('设备标识',
                  cfg.deviceId.length > 8 ? cfg.deviceId.substring(0, 8) : cfg.deviceId),
              const SizedBox(height: 12),
              const Text(
                '请确保电脑与手机连接同一 Wi-Fi，或让电脑连接手机热点。'
                '任一端都可以发起对比与同步。',
                style: TextStyle(
                    fontSize: 12, height: 1.5, color: Color(0xFF9A9A9A)),
              ),
            ],
          ),
        ),
        const SizedBox(height: 20),
        Row(
          children: [
            const Text(
              '电脑设备',
              style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
            ),
            const SizedBox(width: 8),
            Text(
              '${devices.length} 台',
              style: const TextStyle(fontSize: 12, color: Color(0xFF8A8A8A)),
            ),
          ],
        ),
        const SizedBox(height: 8),
        if (devices.isEmpty)
          const GlassCard(
            child: Row(
              children: [
                SizedBox(
                  width: 18,
                  height: 18,
                  child: ProgressRing(strokeWidth: 2),
                ),
                SizedBox(width: 12),
                Expanded(
                  child: Text(
                    '正在搜索同一局域网内的电脑…',
                    style: TextStyle(fontSize: 13, color: Color(0xFF9A9A9A)),
                  ),
                ),
              ],
            ),
          )
        else
          for (final d in devices) ...[
            _deviceTile(d),
            const SizedBox(height: 8),
          ],
        const SizedBox(height: 12),
        const Text(
          '同步映射',
          style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
        ),
        const SizedBox(height: 8),
        if (mappings.isEmpty)
          const GlassCard(
            child: Text(
              '尚未配置映射。请先在「映射」页添加目标目录，'
              '并在电脑端配置同名映射。',
              style: TextStyle(
                  fontSize: 13, height: 1.5, color: Color(0xFF9A9A9A)),
            ),
          )
        else
          GlassCard(
            padding: const EdgeInsets.symmetric(vertical: 6),
            child: Column(
              children: [
                for (var i = 0; i < mappings.length; i++) ...[
                  if (i > 0)
                    Container(height: 1, color: const Color(0x1FFFFFFF)),
                  _mappingItem(mappings[i]),
                ],
              ],
            ),
          ),
      ],
    );
  }

  Widget _infoRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Row(
        children: [
          SizedBox(
            width: 76,
            child: Text(label,
                style:
                    const TextStyle(fontSize: 12, color: Color(0xFF8A8A8A))),
          ),
          Expanded(
            child: Text(value,
                style: const TextStyle(fontSize: 12),
                overflow: TextOverflow.ellipsis),
          ),
        ],
      ),
    );
  }

  Widget _deviceTile(PeerDevice d) {
    final connected = d.connected;
    final busy = _busyId == d.id;
    return GlassCard(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      child: Row(
        children: [
          const Icon(FluentIcons.devices2,
              size: 24, color: Color(0xFF4A9EFF)),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(d.name,
                    style: const TextStyle(
                        fontSize: 14, fontWeight: FontWeight.w600),
                    overflow: TextOverflow.ellipsis),
                const SizedBox(height: 2),
                Text(
                  connected
                      ? '已连接 · 可发起对比与同步'
                      : '协议 v2${d.appVersion.isEmpty ? '' : ' · v${d.appVersion}'}',
                  style: TextStyle(
                    fontSize: 11,
                    color: connected
                        ? const Color(0xFF7BE0A0)
                        : const Color(0xFF8A8A8A),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          if (busy)
            const SizedBox(
              width: 18,
              height: 18,
              child: ProgressRing(strokeWidth: 2),
            )
          else
            Button(
              onPressed: () => _connect(d, connected),
              child: Text(connected ? '断开' : '连接',
                  style: const TextStyle(fontSize: 12)),
            ),
        ],
      ),
    );
  }

  Widget _mappingItem(SyncMapping m) {
    final plan = _plans[m.id];
    final comparing = _comparing[m.id] == true;
    final syncing = _syncing[m.id] == true;
    final connected = _peer != null;
    final String status;
    final Color statusColor;
    if (comparing) {
      status = '对比中…';
      statusColor = const Color(0xFFFFC46B);
    } else if (syncing) {
      status = '同步中…';
      statusColor = const Color(0xFFFFC46B);
    } else if (plan == null) {
      status = connected ? '未对比' : '未连接';
      statusColor = const Color(0xFF8A8A8A);
    } else if (plan.isEmpty) {
      status = '已一致';
      statusColor = const Color(0xFF7BE0A0);
    } else {
      status = '新增 ${plan.newOnly.length} 个';
      statusColor = const Color(0xFF4A9EFF);
    }

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(FluentIcons.folder,
                  size: 18, color: Color(0xFF4A9EFF)),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(m.name, style: const TextStyle(fontSize: 14)),
                    const SizedBox(height: 2),
                    Text(
                      m.destPath,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          fontSize: 11, color: Color(0xFF8A8A8A)),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                decoration: BoxDecoration(
                  color: statusColor.withAlpha(38),
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Text(
                  status,
                  style: TextStyle(fontSize: 11, color: statusColor),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Row(
            mainAxisAlignment: MainAxisAlignment.end,
            children: [
              Button(
                onPressed: connected && !comparing && !syncing
                    ? () => _compare(m)
                    : null,
                child: Text(comparing ? '对比中…' : '对比',
                    style: const TextStyle(fontSize: 12)),
              ),
              const SizedBox(width: 8),
              FilledButton(
                onPressed: connected &&
                        !comparing &&
                        !syncing &&
                        plan != null &&
                        !plan.isEmpty
                    ? () => _sync(m, plan)
                    : null,
                child: Text(syncing ? '同步中…' : '同步',
                    style: const TextStyle(fontSize: 12)),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

/// 同步进度快照。
class _SyncProgressState {
  final int total;
  final int totalBytes;
  final int done;
  final int transferredBytes;
  final String currentPath;
  final bool completed;
  final bool cancelling;
  final bool cancelled;
  final int failed;
  final String? error;

  const _SyncProgressState({
    required this.total,
    required this.totalBytes,
    this.done = 0,
    this.transferredBytes = 0,
    this.currentPath = '',
    this.completed = false,
    this.cancelling = false,
    this.cancelled = false,
    this.failed = 0,
    this.error,
  });

  _SyncProgressState copyWith({
    int? done,
    int? transferredBytes,
    String? currentPath,
    bool? completed,
    bool? cancelling,
    bool? cancelled,
    int? failed,
    String? error,
  }) {
    return _SyncProgressState(
      total: total,
      totalBytes: totalBytes,
      done: done ?? this.done,
      transferredBytes: transferredBytes ?? this.transferredBytes,
      currentPath: currentPath ?? this.currentPath,
      completed: completed ?? this.completed,
      cancelling: cancelling ?? this.cancelling,
      cancelled: cancelled ?? this.cancelled,
      failed: failed ?? this.failed,
      error: error ?? this.error,
    );
  }
}

/// 同步进度弹窗：平滑进度条 + 文件计数 + 当前文件，完成后需手动关闭。
class _SyncProgressDialog extends StatelessWidget {
  final String mappingName;
  final ValueNotifier<_SyncProgressState> progress;
  final VoidCallback onCancel;

  const _SyncProgressDialog({
    required this.mappingName,
    required this.progress,
    required this.onCancel,
  });

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<_SyncProgressState>(
      valueListenable: progress,
      builder: (context, p, _) {
        final double fraction = (p.totalBytes > 0
                ? (p.transferredBytes / p.totalBytes)
                : (p.total > 0 ? p.done / p.total : 0.0))
            .clamp(0.0, 1.0)
            .toDouble();
        final title = p.completed
            ? (p.error != null
                ? '同步失败'
                : p.cancelled
                    ? '已取消同步'
                    : p.failed > 0
                        ? '同步完成（部分失败）'
                        : '同步完成')
            : '同步中 · $mappingName';
        return GlassDialog(
          title: title,
          maxWidth: 400,
          content: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              _AnimatedProgressBar(
                value: p.completed && !p.cancelled ? 1.0 : fraction,
              ),
              const SizedBox(height: 10),
              Text(
                p.completed
                    ? '成功 ${(p.done - p.failed).clamp(0, p.total)} 个 · '
                        '失败 ${p.failed} 个 · ${formatBytes(p.transferredBytes)}'
                    : '已处理 ${p.done}/${p.total} · '
                        '${(fraction * 100).toStringAsFixed(0)}%',
                style: const TextStyle(fontSize: 12, height: 1.4),
              ),
              if (!p.completed && p.currentPath.isNotEmpty) ...[
                const SizedBox(height: 4),
                Text(
                  p.currentPath,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                      fontSize: 11, color: Color(0xFF9A9A9A)),
                ),
              ],
              if (p.error != null) ...[
                const SizedBox(height: 8),
                Text(
                  '原因：${p.error}',
                  style: const TextStyle(
                      fontSize: 12, height: 1.4, color: Color(0xFFFF9B9B)),
                ),
              ],
              if (p.completed && p.failed > 0) ...[
                const SizedBox(height: 4),
                const Text(
                  '可重新对比后重试失败文件。',
                  style: TextStyle(fontSize: 11, color: Color(0xFF9A9A9A)),
                ),
              ],
              if (p.completed && p.cancelled) ...[
                const SizedBox(height: 4),
                const Text(
                  '已完成的文件会保留在目标目录。',
                  style: TextStyle(fontSize: 11, color: Color(0xFF9A9A9A)),
                ),
              ],
            ],
          ),
          actions: p.completed
              ? [
                  FilledButton(
                    child: const Text('关闭'),
                    onPressed: () => Navigator.pop(context),
                  ),
                ]
              : [
                  Button(
                    onPressed: p.cancelling ? null : onCancel,
                    child: Text(p.cancelling ? '正在取消…' : '取消',
                        style: const TextStyle(fontSize: 12)),
                  ),
                ],
        );
      },
    );
  }
}

/// 带平滑过渡的胶囊进度条（高度 12）。
class _AnimatedProgressBar extends StatelessWidget {
  final double value;

  const _AnimatedProgressBar({required this.value});

  @override
  Widget build(BuildContext context) {
    return TweenAnimationBuilder<double>(
      tween: Tween<double>(begin: 0, end: value),
      duration: const Duration(milliseconds: 320),
      curve: Curves.easeOut,
      builder: (context, v, _) {
        return SizedBox(
          height: 12,
          child: ClipRRect(
            borderRadius: BorderRadius.circular(6),
            child: Stack(
              fit: StackFit.expand,
              children: [
                Container(color: const Color(0x33FFFFFF)),
                Align(
                  alignment: Alignment.centerLeft,
                  child: FractionallySizedBox(
                    heightFactor: 1,
                    widthFactor: v.clamp(0.0, 1.0).toDouble(),
                    child: Container(
                      decoration: const BoxDecoration(
                        gradient: LinearGradient(
                          colors: [Color(0xFF4A9EFF), Color(0xFF7C5CFF)],
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

/// 自动关闭的居中玻璃提示（不显示 IP）。
class _AutoCloseNotice extends StatefulWidget {
  final bool ok;
  final String title;
  final String text;

  const _AutoCloseNotice({
    required this.ok,
    required this.title,
    required this.text,
  });

  @override
  State<_AutoCloseNotice> createState() => _AutoCloseNoticeState();
}

class _AutoCloseNoticeState extends State<_AutoCloseNotice> {
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _timer = Timer(const Duration(milliseconds: 1300), () {
      if (mounted) Navigator.of(context).pop();
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final color =
        widget.ok ? const Color(0xFF7BE0A0) : const Color(0xFFFF9B9B);
    return GlassDialog(
      title: widget.title,
      maxWidth: 300,
      content: Row(
        children: [
          Icon(
            widget.ok ? FluentIcons.check_mark : FluentIcons.error,
            size: 20,
            color: color,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(widget.text,
                style: const TextStyle(fontSize: 13, height: 1.4)),
          ),
        ],
      ),
    );
  }
}
