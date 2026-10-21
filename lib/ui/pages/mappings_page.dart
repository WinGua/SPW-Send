import 'package:fluent_ui/fluent_ui.dart';

import '../../app_scope.dart';
import '../../core/utils.dart';
import '../../models/sync_mapping.dart';
import '../../platform/folder_picker.dart';
import '../../platform/permissions.dart';
import '../widgets/glass.dart';

/// 映射页：维护「映射名称 → 手机目标目录」。
class MappingsPage extends StatefulWidget {
  const MappingsPage({super.key});

  @override
  State<MappingsPage> createState() => _MappingsPageState();
}

class _MappingsPageState extends State<MappingsPage> {
  Future<void> _openEditor({SyncMapping? existing}) async {
    final nameCtrl = TextEditingController(text: existing?.name ?? '');
    final destCtrl = TextEditingController(text: existing?.destPath ?? '');
    final result = await showGlassDialog<SyncMapping>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => _MappingEditor(
        existing: existing,
        nameCtrl: nameCtrl,
        destCtrl: destCtrl,
        onSaved: (m) => Navigator.pop(ctx, m),
        onCancel: () => Navigator.pop(ctx),
      ),
    );
    disposeControllerLater(nameCtrl);
    disposeControllerLater(destCtrl);
    if (result == null) return;
    if (existing == null) {
      scope.config.addMapping(result);
    } else {
      scope.config.updateMapping(result);
    }
    setState(() {});
  }

  Future<void> _delete(SyncMapping mapping) async {
    final ok = await showGlassDialog<bool>(
      context: context,
      builder: (ctx) => GlassDialog(
        title: '删除映射',
        content: Text('确定删除「${mapping.name}」吗？手机上的文件不会被删除。'),
        actions: [
          Button(
            child: const Text('取消'),
            onPressed: () => Navigator.pop(ctx, false),
          ),
          FilledButton(
            child: const Text('删除'),
            onPressed: () => Navigator.pop(ctx, true),
          ),
        ],
      ),
    );
    if (ok != true) return;
    scope.config.removeMapping(mapping.id);
    setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final mappings = scope.config.config.mappings;
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 24),
      children: [
        Row(
          children: [
            const Expanded(
              child: Text(
                '映射',
                style: TextStyle(fontSize: 24, fontWeight: FontWeight.w600),
              ),
            ),
            FilledButton(
              onPressed: () => _openEditor(),
              child: const Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(FluentIcons.add, size: 14),
                  SizedBox(width: 6),
                  Text('新增映射', style: TextStyle(fontSize: 12)),
                ],
              ),
            ),
          ],
        ),
        const SizedBox(height: 6),
        const Text(
          '映射名称需与电脑端保持一致；手机端只配置目标目录，对比与同步可在任一端发起。',
          style: TextStyle(fontSize: 12, height: 1.5, color: Color(0xFF8A8A8A)),
        ),
        const SizedBox(height: 16),
        if (mappings.isEmpty)
          const GlassCard(
            child: Text(
              '暂无映射。点击右上角「新增映射」，选择手机上的目标文件夹。',
              style: TextStyle(
                  fontSize: 13, height: 1.5, color: Color(0xFF9A9A9A)),
            ),
          )
        else
          for (final m in mappings) ...[
            GlassCard(
              padding: const EdgeInsets.all(14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      const Icon(FluentIcons.folder,
                          size: 20, color: Color(0xFF4A9EFF)),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          m.name,
                          style: const TextStyle(
                              fontSize: 15, fontWeight: FontWeight.w600),
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                      IconButton(
                        icon: const Icon(FluentIcons.edit, size: 16),
                        onPressed: () => _openEditor(existing: m),
                      ),
                      IconButton(
                        icon: const Icon(FluentIcons.delete, size: 16),
                        onPressed: () => _delete(m),
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Text(
                    m.destPath,
                    style: const TextStyle(
                        fontSize: 12, color: Color(0xFF8A8A8A)),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 8),
          ],
      ],
    );
  }
}

/// 映射编辑弹窗。
class _MappingEditor extends StatefulWidget {
  final SyncMapping? existing;
  final TextEditingController nameCtrl;
  final TextEditingController destCtrl;
  final void Function(SyncMapping) onSaved;
  final VoidCallback onCancel;

  const _MappingEditor({
    required this.existing,
    required this.nameCtrl,
    required this.destCtrl,
    required this.onSaved,
    required this.onCancel,
  });

  @override
  State<_MappingEditor> createState() => _MappingEditorState();
}

class _MappingEditorState extends State<_MappingEditor> {
  String? _error;
  bool _picking = false;

  Future<void> _pickFolder() async {
    setState(() => _picking = true);
    final granted = await ensureFileAccess();
    if (!granted) {
      if (mounted) {
        setState(() {
          _picking = false;
          _error = '未获得文件访问权限，请在系统设置中开启「所有文件访问」后重试。';
        });
      }
      return;
    }
    final picked = await FolderPicker.pick();
    if (!mounted) return;
    setState(() {
      _picking = false;
      if (picked != null && picked.isNotEmpty) {
        widget.destCtrl.text = picked;
        _error = null;
      }
    });
  }

  void _save() {
    final name = widget.nameCtrl.text.trim();
    final dest = widget.destCtrl.text.trim();
    if (name.isEmpty) {
      setState(() => _error = '请填写映射名称。');
      return;
    }
    final duplicated = scope.config.config.mappings.any((m) =>
        m.name == name && m.id != widget.existing?.id);
    if (duplicated) {
      setState(() => _error = '映射名称已存在，请换一个名称。');
      return;
    }
    if (dest.isEmpty) {
      setState(() => _error = '请选择手机目标目录。');
      return;
    }
    final id = widget.existing?.id ?? newUuid();
    widget.onSaved(SyncMapping(id: id, name: name, destPath: dest));
  }

  @override
  Widget build(BuildContext context) {
    return GlassDialog(
      title: widget.existing == null ? '新增映射' : '编辑映射',
      maxWidth: 440,
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('映射名称（需与电脑端一致）',
              style: TextStyle(fontSize: 12, color: Color(0xFF9A9A9A))),
          const SizedBox(height: 6),
          TextBox(
            controller: widget.nameCtrl,
            placeholder: '例如：音乐',
          ),
          const SizedBox(height: 14),
          const Text('手机目标目录',
              style: TextStyle(fontSize: 12, color: Color(0xFF9A9A9A))),
          const SizedBox(height: 6),
          Row(
            children: [
              Expanded(
                child: TextBox(
                  controller: widget.destCtrl,
                  placeholder: '点击右侧按钮选择',
                  readOnly: true,
                ),
              ),
              const SizedBox(width: 8),
              Button(
                onPressed: _picking ? null : _pickFolder,
                child: _picking
                    ? const SizedBox(
                        width: 14,
                        height: 14,
                        child: ProgressRing(strokeWidth: 2),
                      )
                    : const Text('选择', style: TextStyle(fontSize: 12)),
              ),
            ],
          ),
          if (_error != null) ...[
            const SizedBox(height: 10),
            Text(
              _error!,
              style: const TextStyle(fontSize: 12, color: Color(0xFFFF9B9B)),
            ),
          ],
        ],
      ),
      actions: [
        Button(child: const Text('取消'), onPressed: widget.onCancel),
        FilledButton(child: const Text('保存'), onPressed: _save),
      ],
    );
  }
}
