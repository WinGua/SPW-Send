import 'dart:convert';
import 'dart:io';

import 'package:fluent_ui/fluent_ui.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../app_scope.dart';
import '../../core/constants.dart';
import '../../core/utils.dart';
import '../widgets/glass.dart';
import 'about_page.dart';
import 'sync_log_page.dart';

/// 设置页：设备名、端口、同步日志、关于、仓库与检查更新。
class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key});

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  bool _checking = false;

  Future<void> _editDeviceName() async {
    final ctrl = TextEditingController(text: scope.config.config.deviceName);
    final name = await showGlassDialog<String>(
      context: context,
      builder: (ctx) => GlassDialog(
        title: '设备名称',
        maxWidth: 380,
        content: TextBox(
          controller: ctrl,
          placeholder: '例如：我的手机',
        ),
        actions: [
          Button(child: const Text('取消'), onPressed: () => Navigator.pop(ctx)),
          FilledButton(
            child: const Text('保存'),
            onPressed: () => Navigator.pop(ctx, ctrl.text.trim()),
          ),
        ],
      ),
    );
    disposeControllerLater(ctrl);
    if (name == null || name.isEmpty) return;
    scope.config.setDeviceName(name);
    await _restartServices();
  }

  Future<void> _editPort() async {
    final ctrl = TextEditingController(text: '${scope.config.config.transferPort}');
    final raw = await showGlassDialog<String>(
      context: context,
      builder: (ctx) => GlassDialog(
        title: '服务端口',
        maxWidth: 380,
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            TextBox(
              controller: ctrl,
              placeholder: '54318',
            ),
            const SizedBox(height: 8),
            const Text(
              '默认 54318，范围 1024–65535。修改后会重启局域网服务。',
              style: TextStyle(fontSize: 12, color: Color(0xFF8A8A8A)),
            ),
          ],
        ),
        actions: [
          Button(child: const Text('取消'), onPressed: () => Navigator.pop(ctx)),
          FilledButton(
            child: const Text('保存'),
            onPressed: () => Navigator.pop(ctx, ctrl.text.trim()),
          ),
        ],
      ),
    );
    disposeControllerLater(ctrl);
    if (raw == null) return;
    final port = int.tryParse(raw);
    if (port == null || port < 1024 || port > 65535) {
      await _notice('端口无效', '请输入 1024–65535 之间的端口号。');
      return;
    }
    scope.config.setTransferPort(port);
    await _restartServices();
    await _notice('已保存', '服务端口已切换为 $port。');
  }

  Future<void> _restartServices() async {
    await scope.startServices();
    if (mounted) setState(() {});
  }

  Future<void> _openUrl(String url) async {
    try {
      await launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication);
    } catch (_) {
      if (mounted) await _notice('无法打开链接', url);
    }
  }

  Future<void> _checkUpdate() async {
    if (_checking) return;
    setState(() => _checking = true);
    String? latest;
    try {
      final client = HttpClient()
        ..connectionTimeout = const Duration(seconds: 10);
      try {
        final req = await client.getUrl(
          Uri.parse(AppConstants.releasesApiUrl),
        );
        req.headers.set(HttpHeaders.userAgentHeader, 'SPW-Send');
        final res = await req.close();
        final text = await res.transform(utf8.decoder).join();
        if (res.statusCode == 200) {
          final j = jsonDecode(text);
          if (j is Map) latest = j['tag_name']?.toString();
        }
      } finally {
        client.close(force: true);
      }
    } catch (_) {
      latest = null;
    }
    if (!mounted) return;
    setState(() => _checking = false);

    if (latest == null) {
      await _notice('检查更新失败', '无法获取版本信息，请检查网络后重试。');
      return;
    }
    final remote = latest.replaceFirst(RegExp('^[vV]'), '');
    if (_compareVersion(remote, AppConstants.currentVersion) <= 0) {
      await _notice('已是最新版本', '当前版本 ${AppConstants.currentVersion}');
      return;
    }
    final open = await showGlassDialog<bool>(
      context: context,
      builder: (ctx) => GlassDialog(
        title: '发现新版本',
        content: Text('最新版本 $remote，当前版本 ${AppConstants.currentVersion}。'),
        actions: [
          Button(
            child: const Text('稍后'),
            onPressed: () => Navigator.pop(ctx, false),
          ),
          FilledButton(
            child: const Text('前往下载'),
            onPressed: () => Navigator.pop(ctx, true),
          ),
        ],
      ),
    );
    if (open == true) await _openUrl(AppConstants.releasesUrl);
  }

  Future<void> _notice(String title, String text) {
    return showGlassDialog<void>(
      context: context,
      builder: (ctx) => GlassDialog(
        title: title,
        maxWidth: 340,
        content: Text(text, style: const TextStyle(fontSize: 13, height: 1.4)),
        actions: [
          FilledButton(
            child: const Text('知道了'),
            onPressed: () => Navigator.pop(ctx),
          ),
        ],
      ),
    );
  }

  static int _compareVersion(String a, String b) {
    List<int> parse(String v) => v
        .split(RegExp(r'[.+-]'))
        .map((e) => int.tryParse(e) ?? 0)
        .toList();
    final pa = parse(a);
    final pb = parse(b);
    for (var i = 0; i < 3; i++) {
      final x = i < pa.length ? pa[i] : 0;
      final y = i < pb.length ? pb[i] : 0;
      if (x != y) return x.compareTo(y);
    }
    return 0;
  }

  @override
  Widget build(BuildContext context) {
    final cfg = scope.config.config;
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 24),
      children: [
        const Text(
          '设置',
          style: TextStyle(fontSize: 24, fontWeight: FontWeight.w600),
        ),
        const SizedBox(height: 16),
        GlassCard(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: Column(
            children: [
              _tile(
                icon: FluentIcons.rename,
                title: '设备名称',
                subtitle: cfg.deviceName,
                onTap: _editDeviceName,
              ),
              _divider(),
              _tile(
                icon: FluentIcons.plug_connected,
                title: '服务端口',
                subtitle: '${cfg.transferPort}',
                onTap: _editPort,
              ),
              _divider(),
              _tile(
                icon: FluentIcons.history,
                title: '同步日志',
                subtitle: '仅保留最近 7 天',
                onTap: () => Navigator.push(
                  context,
                  FluentPageRoute(builder: (_) => const SyncLogPage()),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        GlassCard(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: Column(
            children: [
              _tile(
                icon: FluentIcons.info,
                title: '关于',
                subtitle: 'SPW-Send ${AppConstants.currentVersion}',
                onTap: () => Navigator.push(
                  context,
                  FluentPageRoute(builder: (_) => const AboutPage()),
                ),
              ),
              _divider(),
              _tile(
                icon: FluentIcons.globe,
                title: 'GitHub 仓库',
                subtitle: 'WinGua/SPW-Send',
                onTap: () => _openUrl(AppConstants.repoUrl),
              ),
              _divider(),
              _tile(
                icon: FluentIcons.sync,
                title: '检查更新',
                subtitle: _checking ? '正在检查…' : '与 GitHub Releases 比对版本',
                trailing: _checking
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: ProgressRing(strokeWidth: 2),
                      )
                    : null,
                onTap: _checking ? null : _checkUpdate,
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _divider() => Container(
        height: 1,
        margin: const EdgeInsets.symmetric(horizontal: 14),
        color: const Color(0x14FFFFFF),
      );

  Widget _tile({
    required IconData icon,
    required String title,
    required String subtitle,
    VoidCallback? onTap,
    Widget? trailing,
  }) {
    return PressableScale(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        child: Row(
          children: [
            Icon(icon, size: 18, color: const Color(0xFF4A9EFF)),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: const TextStyle(fontSize: 14)),
                  const SizedBox(height: 2),
                  Text(
                    subtitle,
                    style: const TextStyle(
                        fontSize: 11, color: Color(0xFF8A8A8A)),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
              ),
            ),
            if (trailing != null)
              trailing
            else
              const Icon(FluentIcons.chevron_right,
                  size: 12, color: Color(0xFF6F6F6F)),
          ],
        ),
      ),
    );
  }
}
