import 'package:flutter_test/flutter_test.dart';
import 'package:wanees/core/config/server_url.dart';

void main() {
  test('server addresses are normalized', () {
    expect(normalizeServerUrl('192.168.1.10:8080'), 'http://192.168.1.10:8080');
    expect(normalizeServerUrl(' https://api.wanees.sy/ '), 'https://api.wanees.sy');
    expect(normalizeServerUrl('http://10.0.2.2:8080/v1/'), 'http://10.0.2.2:8080');
    expect(normalizeServerUrl(''), '');
  });
}
