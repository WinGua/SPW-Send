import 'package:fluent_ui/fluent_ui.dart';

import '../../app_scope.dart';
import '../../core/utils.dart';
import '../../storage/sync_log_repository.dart';
import '../widgets/glass.dart';

/// 同步日志页：展示对端推送/本机记录的同步任务，保留最近 7 天。
class SyncLogPage extends StatefulWidget {
  const SyncLogPage({super.key});

  @override
  State<SyncLogPage> createState() => _SyncLogPageState();
}

class _SyncLogPageState extends State<SyncLogPage> {
  @override
  void initState() {
    super.initState();
    scope.syncLog.addListener(_onChanged);
  }

  @override
  void dispose() {
    scope.syncLog.removeListener(_onChanged);
    super.dispose();
  }

  void _onChanged() {
    if (mounted) setState(() {});
  }

  String _fmt(int ms) {
    final t = DateTime.fromMillisecondsSinceEpoch(ms);
    String two(int v) => v.toString().padLeft(2, '0');
    return '${t.year}-${two(t.month)}-${two(t.day)} ${two(t.hour)}:${two(t.minute)}';
  }

  Future<void> _clear() async {
    final ok = await showGlassDialog<bool>(
      context: context,
      builder: (ctx) => GlassDialog(
        title: '清空日志',
        content: const Text('确定清空本机保存的全部同步日志吗？'),
        actions: [
          Button(
            child: const Text('取消'),
            onPressed: () => Navigator.pop(ctx, false),
          ),
          FilledButton(
            child: const Text('清空'),
            onPressed: () => Navigator.pop(ctx, true),
          ),
        ],
      ),
    );
    if (ok == true) await scope.syncLog.clear();
  }

  @override
  Widget build(BuildContext context) {
    final entries = scope.syncLog.entries;
    return Stack(
      children: [
        const Positioned.fill(child: GradientBackground()),
        SafeArea(
          child: Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(8, 8, 12, 4),
                child: Row(
                  children: [
                    IconButton(
                      icon: const Icon(FluentIcons.back, size: 16),
                      onPressed: () => Navigator.pop(context),
                    ),
                    const SizedBox(width: 4),
                    const Expanded(
                      child: Text(
                        '同步日志',
                        style: TextStyle(
                            fontSize: 18, fontWeight: FontWeight.w600),
                      ),
                    ),
                    if (entries.isNotEmpty)
                      Button(
                        onPressed: _clear,
                        child: const Text('清空', style: TextStyle(fontSize: 12)),
                      ),
                  ],
                ),
              ),
              Expanded(
                child: entries.isEmpty
                    ? const Center(
                        child: Text(
                          '暂无同步记录',
                          style: TextStyle(
                              fontSize: 13, color: Color(0xFF8A8A8A)),
                        ),
                      )
                    : ListView.separated(
                        padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                        itemCount: entries.length,
                        separatorBuilder: (_, __) => const SizedBox(height: 8),
                        itemBuilder: (context, i) => _logCard(entries[i]),
                      ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _logCard(SyncLogEntry e) {
    final failed = e.failed > 0;
    return GlassCard(
      padding: const EdgeInsets.all(14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  e.task.isEmpty ? '同步任务' : e.task,
                  style: const TextStyle(
                      fontSize: 14, fontWeight: FontWeight.w600),
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              Text(
                e.failed > 0 ? '${e.success}/${e.total} 成功' : '${e.success} 个文件',
                style: TextStyle(
                  fontSize: 11,
                  color: failed
                      ? const Color(0xFFFF9B9B)
                      : const Color(0xFF7BE0A0),
                ),
              ),
            ],
          ),
          const SizedBox(height: 6),
          Text(
            '${_fmt(e.finishedAt)} · ${formatBytes(e.bytes)}',
            style: const TextStyle(fontSize: 11, color: Color(0xFF8A8A8A)),
          ),
          if (e.failures.isNotEmpty) ...[
            const SizedBox(height: 8),
            for (final f in e.failures.take(3))
              Padding(
                padding: const EdgeInsets.only(top: 2),
                child: Text(
                  '${f.path}：${f.reason}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                      fontSize: 11, color: Color(0xFFFF9B9B)),
                ),
              ),
            if (e.failures.length > 3)
              Text(
                '等共 ${e.failures.length} 个文件失败',
                style: const TextStyle(
                    fontSize: 11, color: Color(0xFF8A8A8A)),
              ),
          ],
        ],
      ),
    );
  }
}
