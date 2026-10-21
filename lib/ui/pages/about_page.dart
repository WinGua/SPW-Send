import 'package:fluent_ui/fluent_ui.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../core/constants.dart';
import '../widgets/glass.dart';

/// 关于页：应用定位、协议版本与开发声明。
class AboutPage extends StatelessWidget {
  const AboutPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        const Positioned.fill(child: GradientBackground()),
        SafeArea(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(8, 8, 16, 24),
            children: [
              Row(
                children: [
                  IconButton(
                    icon: const Icon(FluentIcons.back, size: 16),
                    onPressed: () => Navigator.pop(context),
                  ),
                  const SizedBox(width: 4),
                  const Text(
                    '关于',
                    style:
                        TextStyle(fontSize: 18, fontWeight: FontWeight.w600),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8),
                child: Column(
                  children: [
                    GlassCard(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Row(
                            children: [
                              Icon(FluentIcons.cell_phone,
                                  size: 26, color: Color(0xFF4A9EFF)),
                              SizedBox(width: 10),
                              Text(
                                'SPW-Send',
                                style: TextStyle(
                                    fontSize: 20,
                                    fontWeight: FontWeight.w600),
                              ),
                            ],
                          ),
                          const SizedBox(height: 10),
                          const Text(
                            '椒盐音乐创意工坊模组的安卓端：'
                            '电脑端与手机端都可发起对比与同步，'
                            '文件仍单向从电脑流向手机，仅新增、不改动、不删除，'
                            '按相对路径保持目录结构。',
                            style: TextStyle(
                                fontSize: 13,
                                height: 1.6,
                                color: Color(0xFFB0B0B0)),
                          ),
                          const SizedBox(height: 14),
                          _line('版本', AppConstants.currentVersion),
                          _line('协议', 'v${AppConstants.protoVersion}'),
                          _line('发现端口', '${AppConstants.discoveryPort}/udp'),
                          _line('传输端口', '默认 ${AppConstants.transferPort}/http'),
                          _line('日志保留', '7 天'),
                        ],
                      ),
                    ),
                    const SizedBox(height: 12),
                    GlassCard(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text(
                            '安全说明',
                            style: TextStyle(
                                fontSize: 14, fontWeight: FontWeight.w600),
                          ),
                          const SizedBox(height: 8),
                          const Text(
                            '首次连接需在手机上确认，确认后加入信任白名单；'
                            '所有传输请求都需要会话 token。'
                            '文件先写入 .part 分片，完整校验通过后才落盘。',
                            style: TextStyle(
                                fontSize: 12,
                                height: 1.6,
                                color: Color(0xFF9A9A9A)),
                          ),
                          const SizedBox(height: 12),
                          Button(
                            onPressed: () async {
                              try {
                                await launchUrl(
                                  Uri.parse(AppConstants.repoUrl),
                                  mode: LaunchMode.externalApplication,
                                );
                              } catch (_) {}
                            },
                            child: const Text('打开 GitHub 仓库',
                                style: TextStyle(fontSize: 12)),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 18),
                    const Text(
                      'Built by Winter with DeepSeek',
                      style:
                          TextStyle(fontSize: 11, color: Color(0xFF6F6F6F)),
                    ),
                    const SizedBox(height: 8),
                  ],
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _line(String label, String value) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Row(
        children: [
          SizedBox(
            width: 72,
            child: Text(label,
                style:
                    const TextStyle(fontSize: 12, color: Color(0xFF8A8A8A))),
          ),
          Expanded(
            child: Text(value, style: const TextStyle(fontSize: 12)),
          ),
        ],
      ),
    );
  }
}
