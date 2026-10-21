import 'package:flutter_test/flutter_test.dart';
import 'package:immich_mobile/domain/models/flat_stereo.dart';
import 'package:immich_mobile/domain/models/network_source.dart';
import 'package:immich_mobile/domain/services/network_media.service.dart';
import 'package:immich_mobile/presentation/widgets/asset_viewer/immersive_viewer.dart';

NetworkEntry _file(String name) => NetworkEntry(sourceId: 'nas', path: '/3D/$name', isDirectory: false);

ImmersiveFolderItem _item(String name) => (entry: _file(name), url: Uri.parse('http://127.0.0.1:8080/m/$name'));

// What a 360° file declares: a photo a 360° camera stitched itself
const _declares360 = NetworkMediaInfo(cameraEquirect: true);

void main() {
  group('flatShareFormat', () {
    test('reads the flat 3D words of a file of a share', () {
      expect(flatShareFormat(_file('beach-da2_RGBD.png')), FlatStereoFormat.rgbd);
      expect(flatShareFormat(_file('beach-da2-SBS.png')), FlatStereoFormat.sbs);
      expect(flatShareFormat(_file('Movie.Half-OU.mkv')), FlatStereoFormat.halfTb);
      expect(flatShareFormat(_file('IMG_1234.JPG')), isNull);
    });

    test('takes the squeezed eyes of a video from the frame size the page knows', () {
      expect(flatShareFormat(_file('movie_SBS.mp4'), width: 1920, height: 1080), FlatStereoFormat.halfSbs);
      expect(flatShareFormat(_file('movie_SBS.mp4'), width: 3840, height: 1080), FlatStereoFormat.sbs);
    });

    test('leaves a side by side file that declares 360° to the sphere, never an RGBD photo', () {
      expect(flatShareFormat(_file('beach-da2-SBS.png'), is360: true), isNull);
      expect(flatShareFormat(_file('Trip 360-da2_RGBD.png'), is360: true), FlatStereoFormat.rgbd);
    });
  });

  group('folderImmersiveRequest', () {
    test('opens a flat 3D file of a share on a flat screen, with its format', () {
      final request = folderImmersiveRequest(_item('beach-da2_RGBD.png'), const NetworkMediaInfo());
      expect(request.flatFormat, FlatStereoFormat.rgbd);
      expect(request.view, stereoPhotoSphereView);
      expect(request.isVideo, isFalse);
      expect(request.url, 'http://127.0.0.1:8080/m/beach-da2_RGBD.png');
    });

    test('opens a squeezed side by side video with the format its frame tells', () {
      final request = folderImmersiveRequest(
        _item('movie_SBS.mp4'),
        const NetworkMediaInfo(),
        width: 1920,
        height: 1080,
      );
      expect(request.flatFormat, FlatStereoFormat.halfSbs);
      expect(request.isVideo, isTrue);
    });

    test('keeps a 360° file and a regular file on the sphere', () {
      expect(folderImmersiveRequest(_item('beach-da2-SBS.png'), _declares360).flatFormat, isNull);
      expect(folderImmersiveRequest(_item('pano.jpg'), _declares360).flatFormat, isNull);
    });
  });
}
