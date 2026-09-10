import 'package:flutter_test/flutter_test.dart';
import 'package:pixfold_saf_spike/main.dart';

void main() {
  testWidgets('spike 页面可渲染(平台通道仅点击时调用,安全)', (tester) async {
    await tester.pumpWidget(const PixFoldSpikeApp());
    expect(find.text('PixFold D2 · SAF Spike'), findsOneWidget);
    expect(find.text('① 选目录'), findsOneWidget);
  });
}
