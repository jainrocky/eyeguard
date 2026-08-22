import 'dart:async';
import 'dart:io';

import 'package:camera/camera.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_mlkit_face_detection/google_mlkit_face_detection.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  final cameras = await availableCameras();

  final frontCamera = cameras.firstWhere(
    (camera) =>
        camera.lensDirection == CameraLensDirection.front,
  );

  runApp(
    EyeGuardApp(
      camera: frontCamera,
    ),
  );
}

// ======================================================
// APP
// ======================================================

class EyeGuardApp extends StatelessWidget {
  final CameraDescription camera;

  const EyeGuardApp({
    super.key,
    required this.camera,
  });

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Eye Guard',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.deepPurple,
        ),
        useMaterial3: true,
      ),
      home: FaceDetectionPage(
        camera: camera,
      ),
    );
  }
}

// ======================================================
// PAGE
// ======================================================

class FaceDetectionPage extends StatefulWidget {
  final CameraDescription camera;

  const FaceDetectionPage({
    super.key,
    required this.camera,
  });

  @override
  State<FaceDetectionPage> createState() =>
      _FaceDetectionPageState();
}

// ======================================================
// STATE
// ======================================================

class _FaceDetectionPageState
    extends State<FaceDetectionPage> {

  // ----------------------------------------------------
  // Native MethodChannel
  // ----------------------------------------------------

  static const MethodChannel _monitoringChannel =
      MethodChannel(
    'com.rockyjain.eyeguard/monitoring',
  );

  // ----------------------------------------------------
  // Camera / ML Kit
  // ----------------------------------------------------

  late CameraController _cameraController;
  late FaceDetector _faceDetector;

  bool _isDetecting = false;

  bool _nativeMonitoring = false;

  bool _isStoppingMonitoring = false;

  bool _cameraDisposed = false;

  // ----------------------------------------------------
  // Flutter prototype warning
  // ----------------------------------------------------

  Timer? _tooCloseTimer;

  bool _showWarning = false;

  double? _latestDistance;

  // ----------------------------------------------------
  // UI state
  // ----------------------------------------------------

  String _status = 'Starting camera...';

  double? _faceWidth;

  double? _faceHeight;

  double? _estimatedDistance;

  int _facesDetected = 0;

  // ----------------------------------------------------
  // Calibration
  // ----------------------------------------------------

  static const double calibrationDistanceCm =
      40.0;

  double? _referenceFaceWidth;

  bool _calibrationComplete = false;

  bool _checkingCalibration = true;

  // ----------------------------------------------------
  // Distance thresholds
  // ----------------------------------------------------

  static const double safeDistanceCm =
      40.0;

  static const double warningDistanceCm =
      35.0;

  static const double tooCloseDistanceCm =
      30.0;

  // ----------------------------------------------------
  // Distance smoothing
  // ----------------------------------------------------

  double? _smoothedDistance;

  // ====================================================
  // INIT
  // ====================================================

  @override
  void initState() {
    super.initState();

    _faceDetector = FaceDetector(
      options: FaceDetectorOptions(
        performanceMode:
            FaceDetectorMode.fast,
        enableTracking: true,
      ),
    );

    _createCameraController();

    _restoreSessionState();
  }

  // ====================================================
  // SESSION RESTORE
  //
  // If the native monitoring service survived an
  // app restart, resume straight into monitoring
  // mode and leave the camera to the service.
  // ====================================================

  Future<void> _restoreSessionState()
      async {

    var monitoring = false;

    try {

      final result =
          await _monitoringChannel
              .invokeMethod<bool>(
        'isMonitoringActive',
      );

      monitoring =
          result ?? false;

    } catch (e) {

      debugPrint(
        'Failed to check monitoring '
        'state: $e',
      );
    }

    if (!mounted) {

      return;
    }

    if (monitoring) {

      setState(() {

        _nativeMonitoring =
            true;

        // The service cannot run without
        // a saved calibration.

        _calibrationComplete =
            true;

        _checkingCalibration =
            false;

        _status =
            'Eye Guard is monitoring';
      });

      return;
    }

    _initializeCamera();

    _loadCalibrationStatus();
  }

  // ====================================================
  // CAMERA CONTROLLER CREATION
  // ====================================================

  void _createCameraController() {

    _cameraController =
        CameraController(
      widget.camera,
      ResolutionPreset.medium,
      enableAudio: false,
      imageFormatGroup:
          Platform.isAndroid
              ? ImageFormatGroup.nv21
              : ImageFormatGroup.bgra8888,
    );

    // ------------------------------------------------
    // The new controller must be released on
    // dispose, even if initialization fails.
    // ------------------------------------------------

    _cameraDisposed =
        false;
  }

  Future<void> _initializeCamera() async {

    _createCameraController();

    try {

      await _cameraController.initialize();

      if (!mounted) {
        return;
      }

      setState(() {
        _status =
            'No face detected';
      });

      await _cameraController
          .startImageStream(
        _processCameraImage,
      );

    } catch (e) {

      if (!mounted) {
        return;
      }

      setState(() {
        _status =
            'Camera error: $e';
      });
    }
  }

  // ====================================================
  // LOAD PERSISTENT CALIBRATION
  // ====================================================

  Future<void> _loadCalibrationStatus() async {

    try {

      final result =
          await _monitoringChannel
              .invokeMethod<bool>(
        'getCalibrationStatus',
      );

      final calibrated =
          result ?? false;

      // ------------------------------------------------
      // Restore the saved reference face width too.
      //
      // Without it the HUD claims calibration is
      // complete while every status check reports
      // "Not calibrated".
      // ------------------------------------------------

      double? referenceWidth;

      if (calibrated) {

        final widthResult =
            await _monitoringChannel
                .invokeMethod<double>(
          'getCalibrationWidth',
        );

        if (widthResult != null &&
            widthResult > 0) {

          referenceWidth =
              widthResult;
        }
      }

      if (!mounted) {
        return;
      }

      final hasCalibration =
          calibrated &&
              referenceWidth != null;

      setState(() {

        _calibrationComplete =
            hasCalibration;

        _referenceFaceWidth =
            referenceWidth;

        _checkingCalibration =
            false;

        if (hasCalibration) {

          _status =
              'Calibration complete';

        } else {

          _status =
              'Please calibrate at 40 cm';
        }
      });

      debugPrint(
        'Calibration status: '
        '$hasCalibration '
        '(reference width: '
        '${referenceWidth?.toStringAsFixed(1) ?? "-"} px)',
      );

    } catch (e) {

      debugPrint(
        'Failed to get calibration status: $e',
      );

      if (!mounted) {
        return;
      }

      setState(() {
        _checkingCalibration =
            false;
      });
    }
  }

  // ====================================================
  // CAMERA IMAGE PROCESSING
  // ====================================================

  Future<void> _processCameraImage(
    CameraImage image,
  ) async {

    if (_isDetecting) {
      return;
    }

    if (_cameraDisposed) {
      return;
    }

    _isDetecting =
        true;

    try {

      final inputImage =
          _convertCameraImage(
        image,
      );

      if (inputImage == null) {
        return;
      }

      final faces =
          await _faceDetector
              .processImage(
        inputImage,
      );

      if (!mounted) {
        return;
      }

      // ------------------------------------------------
      // No face
      // ------------------------------------------------

      if (faces.isEmpty) {

        // --------------------------------------------
        // Face lost while TOO CLOSE
        //
        // A very close face often leaves the
        // detection frame entirely, so losing
        // tracking must never reward leaning
        // in: keep the countdown running and
        // hold the last known reading on screen.
        // --------------------------------------------

        final lastKnownDistance =
            _latestDistance;

        if (
            lastKnownDistance != null &&
            lastKnownDistance <
                warningDistanceCm) {

          setState(() {

            _facesDetected =
                0;

            _faceWidth =
                null;

            _faceHeight =
                null;

            _status =
                _getStatus(
              lastKnownDistance,
            );
          });

          return;
        }

        _tooCloseTimer?.cancel();

        _tooCloseTimer =
            null;

        if (_showWarning) {
          return;
        }

        setState(() {

          _status =
              'No face detected';

          _facesDetected =
              0;

          _faceWidth =
              null;

          _faceHeight =
              null;

          _estimatedDistance =
              null;

          _smoothedDistance =
              null;
        });

        return;
      }

      // ------------------------------------------------
      // Select largest face
      // ------------------------------------------------

      final face =
          faces.reduce(
        (current, next) {

          final currentArea =
              current.boundingBox.width *
              current.boundingBox.height;

          final nextArea =
              next.boundingBox.width *
              next.boundingBox.height;

          return nextArea >
                  currentArea
              ? next
              : current;
        },
      );

      final width =
          face.boundingBox.width;

      final height =
          face.boundingBox.height;

      double? distance;

      // ------------------------------------------------
      // Distance calculation only after calibration
      // ------------------------------------------------

      if (
          _referenceFaceWidth != null &&
          _referenceFaceWidth! > 0 &&
          width > 0) {

        distance =
            calibrationDistanceCm *
            (
              _referenceFaceWidth! /
              width
            );

        _smoothedDistance =
            _smoothDistance(
          distance,
        );

        distance =
            _smoothedDistance;
      }

      // ------------------------------------------------
      // Flutter prototype warning
      // ------------------------------------------------

      _updateWarningState(
        distance,
      );

      // ------------------------------------------------
      // Update UI
      // ------------------------------------------------

      setState(() {

        _facesDetected =
            faces.length;

        _faceWidth =
            width;

        _faceHeight =
            height;

        _estimatedDistance =
            distance;

        _status =
            _getStatus(
          distance,
        );
      });

    } catch (e) {

      debugPrint(
        'Face detection error: $e',
      );

    } finally {

      _isDetecting =
          false;
    }
  }

  // ====================================================
  // CAMERA IMAGE → ML KIT INPUT
  // ====================================================

  InputImage? _convertCameraImage(
    CameraImage image,
  ) {

    try {

      final rotation =
          InputImageRotationValue
              .fromRawValue(
        widget.camera.sensorOrientation,
      );

      if (rotation == null) {
        return null;
      }

      final format =
          InputImageFormatValue
              .fromRawValue(
        image.format.raw,
      );

      if (format == null) {
        return null;
      }

      if (
          Platform.isAndroid &&
          format !=
              InputImageFormat.nv21) {

        debugPrint(
          'Unsupported Android image format: '
          '$format',
        );

        return null;
      }

      if (image.planes.length != 1) {

        debugPrint(
          'Expected 1 plane for NV21, '
          'got ${image.planes.length}',
        );

        return null;
      }

      final plane =
          image.planes.first;

      return InputImage.fromBytes(
        bytes:
            plane.bytes,
        metadata:
            InputImageMetadata(
          size:
              Size(
            image.width.toDouble(),
            image.height.toDouble(),
          ),
          rotation:
              rotation,
          format:
              format,
          bytesPerRow:
              plane.bytesPerRow,
        ),
      );

    } catch (e) {

      debugPrint(
        'InputImage conversion error: $e',
      );

      return null;
    }
  }

  // ====================================================
  // DISTANCE SMOOTHING
  // ====================================================

  double _smoothDistance(
    double newDistance,
  ) {

    if (_smoothedDistance ==
        null) {

      return newDistance;
    }

    const smoothingFactor =
        0.25;

    return (
        _smoothedDistance! *
        (1 - smoothingFactor)
      ) +
      (
        newDistance *
        smoothingFactor
      );
  }

  // ====================================================
  // STATUS
  // ====================================================

  String _getStatus(
    double? distance,
  ) {

    if (!_calibrationComplete ||
        _referenceFaceWidth == null) {

      return 'Not calibrated';
    }

    if (distance == null) {

      return 'Face detected';
    }

    if (
        distance <
        tooCloseDistanceCm) {

      return 'TOO CLOSE';
    }

    if (
        distance <
        warningDistanceCm) {

      return 'Move phone farther away';
    }

    return 'Safe distance';
  }

  // ====================================================
  // STATUS COLOR
  // ====================================================

  Color _getStatusColor(
    double? distance,
  ) {

    if (!_calibrationComplete) {
      return Colors.orange;
    }

    if (distance == null) {
      return Colors.blue;
    }

    if (
        distance <
        tooCloseDistanceCm) {

      return Colors.red;
    }

    if (
        distance <
        warningDistanceCm) {

      return Colors.orange;
    }

    return Colors.green;
  }

  // ====================================================
  // CALIBRATION
  // ====================================================

  // ----------------------------------------------------
  // Recalibration confirmation
  // ----------------------------------------------------

  Future<bool> _confirmRecalibrate()
      async {

    final result =
        await showDialog<bool>(
      context: context,
      builder: (dialogContext) =>
          AlertDialog(

        title: const Text(
          'Recalibrate distance?',
        ),

        content: const Text(
          'Your saved 40 cm calibration '
          'will be replaced.\n\n'
          'Hold the phone 40 cm away '
          'from your face, then '
          'continue.',
        ),

        actions: [

          TextButton(
            onPressed: () =>
                Navigator.of(dialogContext)
                    .pop(false),
            child: const Text(
              'CANCEL',
            ),
          ),

          TextButton(
            onPressed: () =>
                Navigator.of(dialogContext)
                    .pop(true),
            child: const Text(
              'RECALIBRATE',
            ),
          ),
        ],
      ),
    );

    return result ??
        false;
  }

  Future<void> _calibrate() async {

    // --------------------------------------------------
    // Not possible during native monitoring
    //
    // The Flutter camera is released while the
    // native foreground service owns the camera.
    // --------------------------------------------------

    if (_nativeMonitoring) {

      ScaffoldMessenger.of(
        context,
      ).showSnackBar(
        const SnackBar(
          content: Text(
            'Monitoring is active. '
            'Recalibration is not available.',
          ),
        ),
      );

      return;
    }

    // --------------------------------------------------
    // Recalibration replaces the saved reference
    // --------------------------------------------------

    final isRecalibrating =
        _calibrationComplete;

    if (isRecalibrating) {

      final confirmed =
          await _confirmRecalibrate();

      if (!confirmed) {

        return;
      }

      if (!mounted) {

        return;
      }
    }

    // --------------------------------------------------
    // Face required
    // --------------------------------------------------

    if (
        _faceWidth == null ||
        _faceWidth! <= 0) {

      ScaffoldMessenger.of(
        context,
      ).showSnackBar(
        const SnackBar(
          content: Text(
            'Please make sure your face '
            'is visible first.',
          ),
        ),
      );

      return;
    }

    final calibrationWidth =
        _faceWidth!;

    // --------------------------------------------------
    // Save calibration natively
    // --------------------------------------------------

    try {

      await _monitoringChannel
          .invokeMethod(
        'setCalibration',
        {
          'faceWidth':
              calibrationWidth,
        },
      );

      debugPrint(
        'Calibration saved: '
        '${calibrationWidth.toStringAsFixed(1)} px',
      );

    } catch (e) {

      debugPrint(
        'Failed to save native calibration: $e',
      );

      if (!mounted) {
        return;
      }

      ScaffoldMessenger.of(
        context,
      ).showSnackBar(
        SnackBar(
          content: Text(
            'Calibration failed: $e',
          ),
        ),
      );

      return;
    }

    // --------------------------------------------------
    // Update local state
    // --------------------------------------------------

    if (!mounted) {
      return;
    }

    setState(() {

      _referenceFaceWidth =
          calibrationWidth;

      _calibrationComplete =
          true;

      _checkingCalibration =
          false;

      _smoothedDistance =
          calibrationDistanceCm;

      _estimatedDistance =
          calibrationDistanceCm;

      _status =
          'Calibration complete';
    });

    ScaffoldMessenger.of(
      context,
    ).showSnackBar(
      SnackBar(
        content: Text(
          isRecalibrating
              ? 'Recalibrated at '
                  '${calibrationDistanceCm.toInt()} cm '
                  'with face width '
                  '${calibrationWidth.toStringAsFixed(0)} px'
              : 'Calibrated at '
                  '${calibrationDistanceCm.toInt()} cm '
                  'with face width '
                  '${calibrationWidth.toStringAsFixed(0)} px',
        ),
      ),
    );
  }

  // ====================================================
  // REQUEST OVERLAY PERMISSION
  // ====================================================

  Future<bool> _requestOverlayPermission()
      async {

    try {

      final result =
          await _monitoringChannel
              .invokeMethod<bool>(
        'requestOverlayPermission',
      );

      return result ?? false;

    } catch (e) {

      debugPrint(
        'Overlay permission error: $e',
      );

      return false;
    }
  }

  // ====================================================
  // START NATIVE MONITORING
  // ====================================================

  Future<void> _startNativeMonitoring()
      async {

    // --------------------------------------------------
    // Calibration required
    // --------------------------------------------------

    if (!_calibrationComplete) {

      ScaffoldMessenger.of(
        context,
      ).showSnackBar(
        const SnackBar(
          content: Text(
            'Please complete calibration first.',
          ),
        ),
      );

      return;
    }

    // --------------------------------------------------
    // Overlay permission
    // --------------------------------------------------

    final overlayPermission =
        await _requestOverlayPermission();

    if (!mounted) {

      return;
    }

    if (!overlayPermission) {

      ScaffoldMessenger.of(
        context,
      ).showSnackBar(
        const SnackBar(
          content: Text(
            'Please enable Display over other apps '
            'for Eye Guard, then start monitoring again.',
          ),
        ),
      );

      return;
    }

    try {

      // ------------------------------------------------
      // Change Flutter UI first
      //
      // This keeps build() away from the camera
      // preview while the controller is released.
      // ------------------------------------------------

      if (mounted) {

        setState(() {

          _nativeMonitoring =
              true;

          // ------------------------------------------
          // Clear any pending prototype warning.
          //
          // A countdown started at close range must
          // not fire after the native service takes
          // over: no frames arrive during native
          // monitoring, so this overlay could never
          // clear itself.
          // ------------------------------------------

          _tooCloseTimer?.cancel();

          _tooCloseTimer =
              null;

          _showWarning =
              false;

          _latestDistance =
              null;

          _status =
              'Eye Guard is monitoring';
        });
      }

      // ------------------------------------------------
      // Stop Flutter camera stream
      // ------------------------------------------------

      try {

        await _cameraController
            .stopImageStream();

      } catch (_) {
        // Already stopped.
      }

      // ------------------------------------------------
      // Release Flutter camera
      // ------------------------------------------------

      if (!_cameraDisposed) {

        await _cameraController
            .dispose();

        _cameraDisposed =
            true;
      }

      // ------------------------------------------------
      // Notification permission
      // ------------------------------------------------

      await _monitoringChannel
          .invokeMethod(
        'requestNotificationPermission',
      );

      // ------------------------------------------------
      // Start native foreground service
      // ------------------------------------------------

      await _monitoringChannel
          .invokeMethod(
        'startMonitoring',
      );

      if (!mounted) {
        return;
      }

      ScaffoldMessenger.of(
        context,
      ).showSnackBar(
        const SnackBar(
          content: Text(
            'Native Eye Guard monitoring started.',
          ),
        ),
      );

    } catch (e) {

      debugPrint(
        'Failed to start native monitoring: $e',
      );

      if (!mounted) {
        return;
      }

      ScaffoldMessenger.of(
        context,
      ).showSnackBar(
        SnackBar(
          content: Text(
            'Failed to start monitoring: $e',
          ),
        ),
      );
    }
  }

  // ====================================================
  // STOP NATIVE MONITORING
  // ====================================================

  Future<void> _stopNativeMonitoring()
      async {

    // --------------------------------------------------
    // Guard against double taps
    // --------------------------------------------------

    if (_isStoppingMonitoring) {

      return;
    }

    setState(() {

      _isStoppingMonitoring =
          true;
    });

    // --------------------------------------------------
    // Stop the native foreground service
    //
    // The service releases the native camera and
    // removes the warning overlay in onDestroy().
    // --------------------------------------------------

    try {

      await _monitoringChannel
          .invokeMethod(
        'stopMonitoring',
      );

    } catch (e) {

      debugPrint(
        'Failed to stop monitoring: $e',
      );
    }

    if (!mounted) {

      return;
    }

    // --------------------------------------------------
    // Reset prototype warning state
    // --------------------------------------------------

    _tooCloseTimer?.cancel();

    _tooCloseTimer =
        null;

    // --------------------------------------------------
    // Return to the camera preview
    //
    // The Flutter camera was released when
    // monitoring started and must be reopened.
    // --------------------------------------------------

    setState(() {

      _nativeMonitoring =
          false;

      _showWarning =
          false;

      _latestDistance =
          null;

      _smoothedDistance =
          null;

      _estimatedDistance =
          null;

      _faceWidth =
          null;

      _faceHeight =
          null;

      _facesDetected =
          0;

      _status =
          'Restarting camera...';
    });

    await _initializeCamera();

    if (!mounted) {

      return;
    }

    setState(() {

      _isStoppingMonitoring =
          false;

      if (_calibrationComplete) {

        _status =
            'Calibration complete';

      } else {

        _status =
            'Please calibrate at 40 cm';
      }
    });

    ScaffoldMessenger.of(
      context,
    ).showSnackBar(
      const SnackBar(
        content: Text(
          'Eye Guard monitoring stopped.',
        ),
      ),
    );
  }

  // ====================================================
  // NATIVE MONITORING VIEW
  // ====================================================

  Widget _buildNativeMonitoringView() {

    return Container(
      color: Colors.black,
      child: Center(
        child: Column(
          mainAxisAlignment:
              MainAxisAlignment.center,
          children: [

            const Icon(
              Icons.visibility,
              color: Colors.white,
              size: 80,
            ),

            const SizedBox(
              height: 24,
            ),

            const Text(
              'Eye Guard is monitoring',
              textAlign:
                  TextAlign.center,
              style: TextStyle(
                color: Colors.white,
                fontSize: 26,
                fontWeight:
                    FontWeight.bold,
              ),
            ),

            const SizedBox(
              height: 12,
            ),

            const Text(
              'You can continue using your phone.',
              textAlign:
                  TextAlign.center,
              style: TextStyle(
                color: Colors.white70,
                fontSize: 18,
              ),
            ),

            const SizedBox(
              height: 48,
            ),

            OutlinedButton(
              onPressed:
                  _isStoppingMonitoring
                      ? null
                      : _stopNativeMonitoring,
              style:
                  OutlinedButton.styleFrom(
                padding:
                    const EdgeInsets
                        .symmetric(
                  horizontal: 32,
                  vertical: 16,
                ),
                side: const BorderSide(
                  color: Colors.white70,
                ),
                foregroundColor:
                    Colors.white,
              ),
              child:
                  _isStoppingMonitoring
                      ? const SizedBox(
                          width: 20,
                          height: 20,
                          child:
                              CircularProgressIndicator(
                            strokeWidth: 2,
                          ),
                        )
                      : const Text(
                          'STOP MONITORING',
                          style:
                              TextStyle(
                            fontSize: 18,
                            fontWeight:
                                FontWeight.bold,
                          ),
                        ),
            ),
          ],
        ),
      ),
    );
  }

  // ====================================================
  // FLUTTER PROTOTYPE WARNING
  // ====================================================

  void _updateWarningState(
    double? distance,
  ) {

    if (distance == null) {

      _tooCloseTimer?.cancel();

      _tooCloseTimer =
          null;

      return;
    }

    _latestDistance =
        distance;

    // --------------------------------------------------
    // Warning already visible
    // --------------------------------------------------

    if (_showWarning) {

      if (
          distance >=
          safeDistanceCm) {

        setState(() {

          _showWarning =
              false;
        });
      }

      return;
    }

    // --------------------------------------------------
    // Currently safe
    // --------------------------------------------------

    if (
        distance >=
        warningDistanceCm) {

      _tooCloseTimer?.cancel();

      _tooCloseTimer =
          null;

      return;
    }

    // --------------------------------------------------
    // Timer already running
    // --------------------------------------------------

    if (_tooCloseTimer != null) {
      return;
    }

    debugPrint(
      'Too close detected. '
      'Starting 1 second timer.',
    );

    _tooCloseTimer =
        Timer(
      const Duration(
        seconds: 1,
      ),
      () {

        _tooCloseTimer =
            null;

        if (!mounted) {
          return;
        }

        final currentDistance =
            _latestDistance;

        if (
            !_nativeMonitoring &&
            currentDistance != null &&
            currentDistance <
                warningDistanceCm) {

          setState(() {

            _showWarning =
                true;
          });

          debugPrint(
            'TOO CLOSE confirmed. '
            'Showing warning.',
          );
        }
      },
    );
  }

  // ====================================================
  // FLUTTER WARNING UI
  // ====================================================

  Widget _buildWarningOverlay() {

    return Material(
      color: Colors.black,
      child: SafeArea(
        child: Container(
          width:
              double.infinity,
          height:
              double.infinity,
          color:
              Colors.red.shade50,
          child: Column(
            mainAxisAlignment:
                MainAxisAlignment.center,
            children: [

              const Text(
                '👀',
                style: TextStyle(
                  fontSize: 80,
                ),
              ),

              const SizedBox(
                height: 30,
              ),

              const Text(
                'TOO CLOSE!',
                textAlign:
                    TextAlign.center,
                style: TextStyle(
                  fontSize: 42,
                  fontWeight:
                      FontWeight.bold,
                  color: Colors.red,
                ),
              ),

              const SizedBox(
                height: 24,
              ),

              const Padding(
                padding:
                    EdgeInsets.symmetric(
                  horizontal: 32,
                ),
                child: Text(
                  'Please move the phone\n'
                  'a little farther away.',
                  textAlign:
                      TextAlign.center,
                  style: TextStyle(
                    fontSize: 26,
                    fontWeight:
                        FontWeight.w500,
                  ),
                ),
              ),

              const SizedBox(
                height: 40,
              ),

              const Text(
                '📱  ↔️  👧',
                style: TextStyle(
                  fontSize: 48,
                ),
              ),

              const SizedBox(
                height: 40,
              ),

              Text(
  _latestDistance == null
      ? ''
      : 'Distance: '
          '${_latestDistance!.toStringAsFixed(0)} cm',
  style: const TextStyle(
    fontSize: 22,
    color: Colors.black54,
  ),
),
              const SizedBox(
                height: 20,
              ),

              const Text(
                'Move the phone away to continue',
                textAlign:
                    TextAlign.center,
                style: TextStyle(
                  fontSize: 18,
                  color:
                      Colors.black54,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  // ====================================================
  // DISPOSE
  // ====================================================

  @override
  void dispose() {

    _tooCloseTimer?.cancel();

    if (!_cameraDisposed) {

      _cameraController
          .dispose();
    }

    _faceDetector.close();

    super.dispose();
  }

  // ====================================================
  // BUILD
  // ====================================================

  @override
  Widget build(
    BuildContext context,
  ) {

    // --------------------------------------------------
    // Camera initialization
    //
    // Skipped during native monitoring: the Flutter
    // camera is intentionally released there and the
    // monitoring view does not use it.
    //
    // _cameraDisposed is also checked because a
    // released controller still reports itself
    // as initialized until replaced.
    // --------------------------------------------------

    if (!_nativeMonitoring &&
        (!_cameraController
                .value
                .isInitialized ||
            _cameraDisposed)) {

      return const Scaffold(
        body: Center(
          child:
              CircularProgressIndicator(),
        ),
      );
    }

    final statusColor =
        _getStatusColor(
      _estimatedDistance,
    );

    return Stack(
      children: [

        // =================================================
        // MAIN APPLICATION
        // =================================================

        Scaffold(

          appBar: AppBar(
            title:
                const Text(
              'Eye Guard',
            ),
          ),

          body: Stack(
            children: [

              // -------------------------------------------
              // Camera / native monitoring
              // -------------------------------------------

              Positioned.fill(
                child:
                    _nativeMonitoring
                        ? _buildNativeMonitoringView()
                        : CameraPreview(
                            _cameraController,
                          ),
              ),

              // -------------------------------------------
              // Status card
              //
              // Hidden during native monitoring: the
              // Flutter camera is released there, so
              // these values are frozen — and the card
              // would cover the STOP MONITORING button.
              // -------------------------------------------

              if (!_nativeMonitoring)

                Positioned(
                  left: 16,
                  right: 16,
                  bottom: 150,
                  child: Card(
                  elevation: 8,
                  child: Padding(
                    padding:
                        const EdgeInsets.all(
                      16,
                    ),
                    child: Column(
                      children: [

                        Text(
                          _status,
                          textAlign:
                              TextAlign.center,
                          style: TextStyle(
                            fontSize: 24,
                            fontWeight:
                                FontWeight.bold,
                            color:
                                statusColor,
                          ),
                        ),

                        const SizedBox(
                          height: 12,
                        ),

                        Text(
                          'Faces detected: '
                          '$_facesDetected',
                          style:
                              const TextStyle(
                            fontSize: 16,
                          ),
                        ),

                        const SizedBox(
                          height: 6,
                        ),

                        Text(
                          'Face width: '
                          '${_faceWidth?.toStringAsFixed(1) ?? "-"} px',
                        ),

                        const SizedBox(
                          height: 6,
                        ),

                        Text(
                          'Estimated distance: '
                          '${_estimatedDistance?.toStringAsFixed(1) ?? "-"} cm',
                          style:
                              const TextStyle(
                            fontSize: 18,
                            fontWeight:
                                FontWeight.w600,
                          ),
                        ),

                        const SizedBox(
                          height: 10,
                        ),

                        if (_checkingCalibration)

                          const Row(
                            mainAxisAlignment:
                                MainAxisAlignment.center,
                            children: [

                              SizedBox(
                                width: 16,
                                height: 16,
                                child:
                                    CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              ),

                              SizedBox(
                                width: 8,
                              ),

                              Text(
                                'Checking calibration...',
                              ),
                            ],
                          )

                        else if (
                            _calibrationComplete)

                          Column(
                            children: [

                              const Row(
                                mainAxisAlignment:
                                    MainAxisAlignment.center,
                                children: [

                                  Icon(
                                    Icons.check_circle,
                                    color:
                                        Colors.green,
                                    size: 20,
                                  ),

                                  SizedBox(
                                    width: 6,
                                  ),

                                  Text(
                                    'Calibration complete • 40 cm',
                                    style:
                                        TextStyle(
                                      color:
                                          Colors.green,
                                      fontWeight:
                                          FontWeight.bold,
                                    ),
                                  ),
                                ],
                              ),

                              const SizedBox(
                                height: 4,
                              ),

                              TextButton.icon(
                                onPressed:
                                    (_checkingCalibration ||
                                            _nativeMonitoring)
                                        ? null
                                        : _calibrate,
                                icon: const Icon(
                                  Icons.refresh,
                                  size: 18,
                                ),
                                label: const Text(
                                  'RECALIBRATE',
                                ),
                              ),
                            ],
                          )

                        else

                          const Text(
                            'Calibration required',
                            style:
                                TextStyle(
                              color:
                                  Colors.orange,
                              fontWeight:
                                  FontWeight.bold,
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              ),

              // =========================================
              // START MONITORING
              // =========================================

              if (!_nativeMonitoring)

                Positioned(
                  left: 24,
                  right: 24,
                  bottom: 88,
                  child:
                      ElevatedButton(
                    onPressed:
                        _checkingCalibration
                            ? null
                            : _startNativeMonitoring,
                    style:
                        ElevatedButton.styleFrom(
                      padding:
                          const EdgeInsets.symmetric(
                        vertical: 16,
                      ),
                    ),
                    child:
                        const Text(
                      'START MONITORING',
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight:
                            FontWeight.bold,
                      ),
                    ),
                  ),
                ),

              // =========================================
              // CALIBRATION
              // =========================================

              if (
                  !_calibrationComplete &&
                  !_nativeMonitoring)

                Positioned(
                  left: 24,
                  right: 24,
                  bottom: 24,
                  child:
                      ElevatedButton(
                    onPressed:
                        _checkingCalibration
                            ? null
                            : _calibrate,
                    style:
                        ElevatedButton.styleFrom(
                      padding:
                          const EdgeInsets.symmetric(
                        vertical: 16,
                      ),
                    ),
                    child:
                        const Text(
                      'CALIBRATE AT 40 CM',
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight:
                            FontWeight.bold,
                      ),
                    ),
                  ),
                ),

              // =========================================
              // CALIBRATED MESSAGE
              // =========================================

              if (
                  _calibrationComplete &&
                  !_nativeMonitoring)

                const Positioned(
                  left: 24,
                  right: 24,
                  bottom: 24,
                  child: Text(
                    '✓ Calibration saved. '
                    'You can start monitoring.',
                    textAlign:
                        TextAlign.center,
                    style:
                        TextStyle(
                      color:
                          Colors.green,
                      fontWeight:
                          FontWeight.w600,
                    ),
                  ),
                ),
            ],
          ),
        ),

        // =================================================
        // FLUTTER PROTOTYPE WARNING
        //
        // Never shown during native monitoring: the
        // native service owns alerting there and no
        // camera frames arrive to clear this overlay.
        // =================================================

        if (
            _showWarning &&
            !_nativeMonitoring)

          Positioned.fill(
            child:
                _buildWarningOverlay(),
          ),
      ],
    );
  }
}