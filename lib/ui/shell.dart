import 'dart:ui' show ImageFilter;

import 'package:fluent_ui/fluent_ui.dart';

import '../app_scope.dart';
import '../models/device_info.dart';
import 'pages/home_page.dart';
import 'pages/mappings_page.dart';
import 'pages/settings_page.dart';
import 'widgets/glass.dart';

/// 应用导航外壳：手机使用底部导航，平板 / 宽屏使用侧边栏。
class Shell extends StatefulWidget {
  const Shell({super.key});

  @override
  State<Shell> createState() => _ShellState();
}

class _ShellState extends State<Shell> {
  int _index = 0;

  static const List<Widget> _pages = <Widget>[
    HomePage(),
    MappingsPage(),
    SettingsPage(),
  ];

  @override
  void initState() {
    super.initState();
    scope.addListener(_onScopeChanged);
    scope.pairConfirmer = _confirmPair;
  }

  @override
  void dispose() {
    if (scope.pairConfirmer == _confirmPair) scope.pairConfirmer = null;
    scope.removeListener(_onScopeChanged);
    super.dispose();
  }

  void _onScopeChanged() {
    if (!mounted) return;
    setState(() {});
  }

  /// 首次配对确认：只展示设备名称，不含 IP。
  Future<bool> _confirmPair(PeerDevice requester) async {
    if (!mounted) return false;
    final ok = await showGlassDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => GlassDialog(
        title: '连接请求',
        content: Text('「${requester.name}」请求连接本机，是否允许？'),
        actions: [
          Button(
            child: const Text('拒绝'),
            onPressed: () => Navigator.pop(ctx, false),
          ),
          FilledButton(
            child: const Text('允许'),
            onPressed: () => Navigator.pop(ctx, true),
          ),
        ],
      ),
    );
    return ok == true;
  }

  @override
  Widget build(BuildContext context) {
    final width = MediaQuery.of(context).size.width;
    final content = width < 720 ? _buildMobile() : _buildWide();
    return Stack(
      children: [
        const Positioned.fill(child: GradientBackground()),
        content,
      ],
    );
  }

  Widget _buildMobile() {
    return Column(
      children: [
        Expanded(
          child: SafeArea(
            top: true,
            bottom: false,
            child: IndexedStack(index: _index, children: _pages),
          ),
        ),
        _buildBottomBar(),
      ],
    );
  }

  Widget _buildWide() {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _buildSidebar(),
        Expanded(
          child: SafeArea(
            child: IndexedStack(index: _index, children: _pages),
          ),
        ),
      ],
    );
  }

  Widget _buildSidebar() {
    return Container(
      width: 200,
      color: const Color(0x2E1B222E),
      child: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SizedBox(height: 20),
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 18),
              child: Text(
                'SPW-Send',
                style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
              ),
            ),
            const SizedBox(height: 16),
            _sideItem(0, FluentIcons.home, '状态'),
            _sideItem(1, FluentIcons.folder, '映射'),
            const Spacer(),
            _sideItem(2, FluentIcons.settings, '设置'),
            const SizedBox(height: 12),
          ],
        ),
      ),
    );
  }

  Widget _sideItem(int index, IconData icon, String label) {
    final selected = index == _index;
    const accent = Color(0xFF4A9EFF);
    final fg = selected ? accent : const Color(0xFF9A9A9A);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      child: PressableScale(
        onTap: () => setState(() => _index = index),
        child: Container(
          height: 44,
          decoration: BoxDecoration(
            color: selected ? const Color(0x2E4A9EFF) : const Color(0x00000000),
            borderRadius: BorderRadius.circular(8),
          ),
          child: Row(
            children: [
              const SizedBox(width: 12),
              Icon(icon, size: 20, color: fg),
              const SizedBox(width: 12),
              Text(label, style: TextStyle(fontSize: 14, color: fg)),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildBottomBar() {
    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(24),
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: 24, sigmaY: 24),
            child: Container(
              height: 64,
              decoration: BoxDecoration(
                color: const Color(0x33101820),
                borderRadius: BorderRadius.circular(24),
                border: Border.all(color: const Color(0x1FFFFFFF)),
              ),
              child: LayoutBuilder(
                builder: (context, constraints) {
                  final pillWidth = constraints.maxWidth / 3;
                  return Stack(
                    children: [
                      AnimatedPositioned(
                        duration: const Duration(milliseconds: 220),
                        curve: Curves.easeOutCubic,
                        left: _index * pillWidth + 8,
                        top: 8,
                        bottom: 8,
                        width: pillWidth - 16,
                        child: Container(
                          decoration: BoxDecoration(
                            color: const Color(0x334A9EFF),
                            borderRadius: BorderRadius.circular(16),
                          ),
                        ),
                      ),
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          _buildBottomItem(0, FluentIcons.home, '状态'),
                          _buildBottomItem(1, FluentIcons.folder, '映射'),
                          _buildBottomItem(2, FluentIcons.settings, '设置'),
                        ],
                      ),
                    ],
                  );
                },
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildBottomItem(int index, IconData icon, String label) {
    final selected = index == _index;
    final color = selected ? const Color(0xFF4A9EFF) : const Color(0xFF8A8A8A);
    return Expanded(
      child: PressableScale(
        onTap: () => setState(() => _index = index),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 22, color: color),
            const SizedBox(height: 3),
            Text(label, style: TextStyle(fontSize: 11, color: color)),
          ],
        ),
      ),
    );
  }
}
