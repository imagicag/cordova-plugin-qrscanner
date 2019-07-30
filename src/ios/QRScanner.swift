import Foundation
import AVFoundation
import UIKit
import GLKit


let fileName = "NoCloud/qualityParameter.txt"

@objc(QRScanner)
class QRScanner : CDVPlugin, AVCaptureMetadataOutputObjectsDelegate {

    @IBOutlet var qrDecodeLabel: UILabel!
    @IBOutlet var detectorModeSelector: UISegmentedControl!

    var detector: CIDetector?

    class CameraView: UIView {
        var videoPreviewLayer:AVCaptureVideoPreviewLayer?
        var qrCodeFrameView = UIView()

        func interfaceOrientationToVideoOrientation(_ orientation : UIInterfaceOrientation) -> AVCaptureVideoOrientation {
            switch (orientation) {
            case UIInterfaceOrientation.portrait:
                return AVCaptureVideoOrientation.portrait;
            case UIInterfaceOrientation.portraitUpsideDown:
                return AVCaptureVideoOrientation.portraitUpsideDown;
            case UIInterfaceOrientation.landscapeLeft:
                return AVCaptureVideoOrientation.landscapeLeft;
            case UIInterfaceOrientation.landscapeRight:
                return AVCaptureVideoOrientation.landscapeRight;
            default:
                return AVCaptureVideoOrientation.portraitUpsideDown;
            }
        }

        override func layoutSubviews() {
            super.layoutSubviews();

            //            if let sublayers = self.layer.sublayers {
            //                for layer in sublayers {
            //                    layer.frame = self.bounds;
            //                }
            //            }

            self.videoPreviewLayer?.connection?.videoOrientation = interfaceOrientationToVideoOrientation(UIApplication.shared.statusBarOrientation);
        }

        //        override func viewDidLayoutSubviews() {
        //            super.viewDidLayoutSubViews();
        //
        //            let newView = UIView(frame: CGRect(x: 100, y: 100, width: 100, height: 100))
        ////            newView.addConstraints(iBag.constraints)
        //            newView.backgroundColor = UIColor.red
        //            CameraView.addSubview(newView)
        //
        //        }


        func addPreviewLayer(_ previewLayer:AVCaptureVideoPreviewLayer?) {
            previewLayer!.videoGravity = AVLayerVideoGravity.resizeAspectFill
            self.layer.addSublayer(previewLayer!)
            self.videoPreviewLayer = previewLayer;
        }

        func removePreviewLayer() {
            if self.videoPreviewLayer != nil {
                self.videoPreviewLayer!.removeFromSuperlayer()
                self.videoPreviewLayer = nil
            }
        }

    }

    var qrCodeLayer: CAShapeLayer = CAShapeLayer()
    var isScanningBarcodeRunning: Bool = false
    var qualityParameter: Double = 0.5
    var companyURL: String?
    var jsonToSend: QRCodeDataString?

    var cameraView: CameraView!
    var captureSession:AVCaptureSession?
    var captureVideoPreviewLayer:AVCaptureVideoPreviewLayer?
    var metaOutput: AVCaptureMetadataOutput?
    var metaInput: AVCaptureDeviceInput?

    var currentCamera: Int = 0;
    var frontCamera: AVCaptureDevice?
    var backCamera: AVCaptureDevice?

    var scanning: Bool = false
    var paused: Bool = false
    var nextScanningCommand: CDVInvokedUrlCommand?

    enum QRScannerError: Int32 {
        case unexpected_error = 0,
        camera_access_denied = 1,
        camera_access_restricted = 2,
        back_camera_unavailable = 3,
        front_camera_unavailable = 4,
        camera_unavailable = 5,
        scan_canceled = 6,
        light_unavailable = 7,
        open_settings_unavailable = 8
    }

    enum CaptureError: Error {
        case backCameraUnavailable
        case frontCameraUnavailable
        case couldNotCaptureInput(error: NSError)
    }

    enum LightError: Error {
        case torchUnavailable
    }

    override func pluginInitialize() {
        super.pluginInitialize()
        NotificationCenter.default.addObserver(self, selector: #selector(pageDidLoad), name: NSNotification.Name.CDVPageDidLoad, object: nil)
        self.cameraView = CameraView(frame: CGRect(x: 0, y: 0, width: UIScreen.main.bounds.width, height: UIScreen.main.bounds.height))
        self.cameraView.autoresizingMask = [.flexibleWidth, .flexibleHeight];

        qrCodeLayer = CAShapeLayer()
    }

    func sendErrorCode(command: CDVInvokedUrlCommand, error: QRScannerError){
        let pluginResult = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: error.rawValue)
        commandDelegate!.send(pluginResult, callbackId:command.callbackId)
    }

    // utility method
    @objc func backgroundThread(delay: Double = 0.0, background: (() -> Void)? = nil, completion: (() -> Void)? = nil) {
        if #available(iOS 8.0, *) {
            DispatchQueue.global(qos: DispatchQoS.QoSClass.userInitiated).async {
                if (background != nil) {
                    background!()
                }
                DispatchQueue.main.asyncAfter(deadline: DispatchTime.now() + delay * Double(NSEC_PER_SEC)) {
                    if(completion != nil){
                        completion!()
                    }
                }
            }
        } else {
            // Fallback for iOS < 8.0
            if(background != nil){
                background!()
            }
            if(completion != nil){
                completion!()
            }
        }
    }

    @objc func prepScanner(command: CDVInvokedUrlCommand) -> Bool{
        let status = AVCaptureDevice.authorizationStatus(for: AVMediaType.video)
        if (status == AVAuthorizationStatus.restricted) {
            self.sendErrorCode(command: command, error: QRScannerError.camera_access_restricted)
            return false
        } else if status == AVAuthorizationStatus.denied {
            self.sendErrorCode(command: command, error: QRScannerError.camera_access_denied)
            return false
        }
        do {

            if (!isScanningBarcodeRunning) {

                updateQualityParameter()

                cameraView.backgroundColor = UIColor.clear
                //self.webView!.superview!.insertSubview(cameraView, belowSubview: self.webView!)
                //hot fix. atherwise layer wouldn't recognize the coordinates.
                self.webView!.superview!.insertSubview(cameraView, at: 0)
                let availableVideoDevices =  AVCaptureDevice.devices(for: AVMediaType.video)
                for device in availableVideoDevices {
                    if device.position == AVCaptureDevice.Position.back {
                        backCamera = device
                    }
                    else if device.position == AVCaptureDevice.Position.front {
                        frontCamera = device
                    }
                }
                // older iPods have no back camera
                if(backCamera == nil){
                    currentCamera = 1
                }

                captureSession = (UIApplication.shared.delegate as? AppDelegate)?.session
                guard let unwrappedSession = captureSession else {
                    return false
                }
                metaInput = try self.createCaptureDeviceInput()
                if unwrappedSession.canAddInput(metaInput!) {
                    unwrappedSession.addInput(metaInput!)
                } else if let sessionInput = unwrappedSession.inputs.first as? AVCaptureDeviceInput {
                    metaInput = sessionInput
                }
                if let sessionOutput = unwrappedSession.outputs.last as? AVCaptureMetadataOutput {
                    metaOutput = sessionOutput
                } else {
                    metaOutput = AVCaptureMetadataOutput()
                    if unwrappedSession.canAddOutput(metaOutput!) {
                        unwrappedSession.addOutput(metaOutput!)
                    }
                }


                metaOutput!.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
                metaOutput!.metadataObjectTypes = [AVMetadataObject.ObjectType.qr]

                if cameraView.videoPreviewLayer == nil {
                    if let layer = (UIApplication.shared.delegate as? AppDelegate)?.layer {
                        self.captureVideoPreviewLayer = layer
                    } else {
                        let layer = AVCaptureVideoPreviewLayer(session: self.captureSession!)
                        (UIApplication.shared.delegate as? AppDelegate)?.layer = layer
                        self.captureVideoPreviewLayer = layer
                    }
                    self.cameraView.addPreviewLayer(self.captureVideoPreviewLayer)
                    let viewToCheck: UIView = self.webView.superview?.subviews.first{$0 is GLKView} ?? self.cameraView
                    self.captureVideoPreviewLayer?.frame = viewToCheck.frame
                }
                captureSession!.startRunning()
                isScanningBarcodeRunning = true
            }
            return true
        } catch CaptureError.backCameraUnavailable {
            self.sendErrorCode(command: command, error: QRScannerError.back_camera_unavailable)
        } catch CaptureError.frontCameraUnavailable {
            self.sendErrorCode(command: command, error: QRScannerError.front_camera_unavailable)
        } catch CaptureError.couldNotCaptureInput(let error){
            print(error.localizedDescription)
            self.sendErrorCode(command: command, error: QRScannerError.camera_unavailable)
        } catch {
            self.sendErrorCode(command: command, error: QRScannerError.unexpected_error)
        }
        return false
    }

    @objc func createCaptureDeviceInput() throws -> AVCaptureDeviceInput {
        var captureDevice: AVCaptureDevice
        if(currentCamera == 0){
            if(backCamera != nil){
                captureDevice = backCamera!
            } else {
                throw CaptureError.backCameraUnavailable
            }
        } else {
            if(frontCamera != nil){
                captureDevice = frontCamera!
            } else {
                throw CaptureError.frontCameraUnavailable
            }
        }
        let captureDeviceInput: AVCaptureDeviceInput
        do {
            captureDeviceInput = try AVCaptureDeviceInput(device: captureDevice)
        } catch let error as NSError {
            throw CaptureError.couldNotCaptureInput(error: error)
        }
        return captureDeviceInput
    }

    @objc func makeOpaque(){
        self.webView?.isOpaque = false
        self.webView?.backgroundColor = UIColor.clear
    }

    @objc func boolToNumberString(bool: Bool) -> String{
        if(bool) {
            return "1"
        } else {
            return "0"
        }
    }

    @objc func configureLight(command: CDVInvokedUrlCommand, state: Bool){
        var useMode = AVCaptureDevice.TorchMode.on
        if(state == false){
            useMode = AVCaptureDevice.TorchMode.off
        }
        do {
            // torch is only available for back camera
            if(backCamera == nil || backCamera!.hasTorch == false || backCamera!.isTorchAvailable == false || backCamera!.isTorchModeSupported(useMode) == false){
                throw LightError.torchUnavailable
            }
            try backCamera!.lockForConfiguration()
            backCamera!.torchMode = useMode
            backCamera!.unlockForConfiguration()
            self.getStatus(command)
        } catch LightError.torchUnavailable {
            self.sendErrorCode(command: command, error: QRScannerError.light_unavailable)
        } catch let error as NSError {
            print(error.localizedDescription)
            self.sendErrorCode(command: command, error: QRScannerError.unexpected_error)
        }
    }

    ////
    ////
    //// METADATAOUTPUT
    //// --------------
    ////
    ////
    // This method processes metadataObjects captured by iOS.
    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard isScanningBarcodeRunning else {
            return
        }
        qrCodeLayer.removeFromSuperlayer()
        jsonToSend = nil
        if metadataObjects.count == 0 {
            let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: "")
            // while nothing is detected, or if scanning is false, do nothing.
            return commandDelegate!.send(pluginResult, callbackId: nextScanningCommand?.callbackId!)
        }
        let found = metadataObjects[0] as! AVMetadataMachineReadableCodeObject
        if found.type == AVMetadataObject.ObjectType.qr && found.stringValue != nil {
            guard let barCodeObject = cameraView.videoPreviewLayer?.transformedMetadataObject(for: found) as? AVMetadataMachineReadableCodeObject,
                let relation = barCodeObject.corners.relation() else {
                    return
            }
            var color = UIColor.clear
            if let companyURL = companyURL, found.stringValue?.contains(companyURL) ?? false {
                color = UIColor.green
                if Double(relation) < qualityParameter {
                    color = UIColor.red
                }
            } else {
                color = UIColor.clear
            }

            let viewToCheck: UIView = webView.superview?.subviews.first{$0 is GLKView} ?? cameraView
            let updatedCorners = barCodeObject.corners
            var inside = false
            if viewToCheck.frame.contains(updatedCorners) {
                updateQRCodeLayer(updatedCorners, color: color.cgColor)
                viewToCheck.layer.addSublayer(qrCodeLayer)
                //cameraView.layer.addSublayer(qrCodeLayer)
                //captureVideoPreviewLayer?.addSublayer(qrCodeLayer)
                inside = true
            }
            jsonToSend = QRCodeDataString(coords: barCodeObject.corners, text: found.stringValue, relation: Double(relation), inside: inside.int)
            if let jsonData = try? JSONEncoder().encode(jsonToSend),
                let jsonString = String(data: jsonData, encoding: .utf8) {
                print(jsonString)
                let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: jsonString)
                commandDelegate!.send(pluginResult, callbackId: nextScanningCommand?.callbackId!)
            }
            //            nextScanningCommand = nil
        }
    }

    // ----------------------- IMAGE QRCODE DETECTION -----------------------

    func performQRCodeDetection(_ image: CIImage) -> (outImage: CIImage?, decode: String) {
        var resultImage: CIImage?
        var decode = ""
        if let detector = detector {
            let features = detector.features(in: image)
            for feature in features as! [CIQRCodeFeature] {
                resultImage = drawHighlightOverlayForPoints(image, topLeft: feature.topLeft, topRight: feature.topRight,
                                                            bottomLeft: feature.bottomLeft, bottomRight: feature.bottomRight)
                decode = feature.messageString!
            }
        }
        return (resultImage, decode)
    }

    func prepareRectangleDetector() -> CIDetector {
        let options: [String: Any] = [CIDetectorAccuracy: CIDetectorAccuracyHigh, CIDetectorAspectRatio: 1.0]
        return CIDetector(ofType: CIDetectorTypeRectangle, context: nil, options: options)!
    }

    func prepareQRCodeDetector() -> CIDetector {
        let options = [CIDetectorAccuracy: CIDetectorAccuracyHigh]
        return CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: options)!
    }

    func drawHighlightOverlayForPoints(_ image: CIImage, topLeft: CGPoint, topRight: CGPoint,
                                       bottomLeft: CGPoint, bottomRight: CGPoint) -> CIImage {
        var overlay = CIImage(color: CIColor(red: 1.0, green: 0, blue: 0, alpha: 0.5))
        overlay = overlay.cropped(to: image.extent)
        overlay = overlay.applyingFilter("CIPerspectiveTransformWithExtent",
                                         parameters: [
                                            "inputExtent": CIVector(cgRect: image.extent),
                                            "inputTopLeft": CIVector(cgPoint: topLeft),
                                            "inputTopRight": CIVector(cgPoint: topRight),
                                            "inputBottomLeft": CIVector(cgPoint: bottomLeft),
                                            "inputBottomRight": CIVector(cgPoint: bottomRight)
            ])
        return overlay.composited(over: image)
    }

    // ----------------------- IMAGE QRCODE DETECTION -----------------------

    @objc func pageDidLoad() {
        self.webView?.isOpaque = false
        self.webView?.backgroundColor = UIColor.clear
    }

    // ---- BEGIN EXTERNAL API ----

    @objc func prepare(_ command: CDVInvokedUrlCommand){
        let status = AVCaptureDevice.authorizationStatus(for: AVMediaType.video)
        if (status == AVAuthorizationStatus.notDetermined) {
            // Request permission before preparing scanner
            AVCaptureDevice.requestAccess(for: AVMediaType.video, completionHandler: { (granted) -> Void in
                // attempt to prepScanner only after the request returns
                self.backgroundThread(delay: 0, completion: {
                    if(self.prepScanner(command: command)){
                        self.getStatus(command)
                    }
                })
            })
        } else {
            if(self.prepScanner(command: command)){
                self.getStatus(command)
            }
        }
    }

    @objc func getQuality(_ command: CDVInvokedUrlCommand) {
        print("\(command.arguments)")
        self.getStatus(command)
    }

    @objc func scan(_ command: CDVInvokedUrlCommand){
        if(self.prepScanner(command: command)){
            nextScanningCommand = command
            scanning = true
        }
    }

    @objc func cancelScan(_ command: CDVInvokedUrlCommand){
        //why this method is called during scanning? need to debug
        //print("cancel")

        //        if(self.prepScanner(command: command)){
        //            scanning = false
        //            if(nextScanningCommand != nil){
        //                self.sendErrorCode(command: nextScanningCommand!, error: QRScannerError.scan_canceled)
        //            }
        //            self.getStatus(command)
        //        }
        //qrCodeLayer.removeFromSuperlayer()
    }

    @objc func show(_ command: CDVInvokedUrlCommand) {
        self.webView?.isOpaque = false
        self.webView?.backgroundColor = UIColor.clear
        self.getStatus(command)
    }

    @objc func hide(_ command: CDVInvokedUrlCommand) {
        self.makeOpaque()
        cameraView.removePreviewLayer()
        qrCodeLayer.removeFromSuperlayer()
        self.isScanningBarcodeRunning = false
        self.getStatus(command)
    }

    @objc func pausePreview(_ command: CDVInvokedUrlCommand) {
        if(scanning){
            paused = true;
            scanning = false;
        }
        captureVideoPreviewLayer?.connection?.isEnabled = false
        self.getStatus(command)
    }

    @objc func resumePreview(_ command: CDVInvokedUrlCommand) {
        if(paused){
            paused = false;
            scanning = true;
        }
        captureVideoPreviewLayer?.connection?.isEnabled = true
        self.getStatus(command)
    }

    // backCamera is 0, frontCamera is 1

    @objc func useCamera(_ command: CDVInvokedUrlCommand){
        let index = command.arguments[0] as! Int
        if(currentCamera != index){
            // camera change only available if both backCamera and frontCamera exist
            if(backCamera != nil && frontCamera != nil){
                // switch camera
                currentCamera = index
                if(self.prepScanner(command: command)){
                    do {
                        captureSession!.beginConfiguration()
                        let currentInput = captureSession?.inputs[0] as! AVCaptureDeviceInput

                        metaInput = currentInput
                        qrCodeLayer.removeFromSuperlayer()

                        captureSession!.commitConfiguration()
                        self.getStatus(command)
                    } catch CaptureError.backCameraUnavailable {
                        self.sendErrorCode(command: command, error: QRScannerError.back_camera_unavailable)
                    } catch CaptureError.frontCameraUnavailable {
                        self.sendErrorCode(command: command, error: QRScannerError.front_camera_unavailable)
                    } catch CaptureError.couldNotCaptureInput(let error){
                        print(error.localizedDescription)
                        self.sendErrorCode(command: command, error: QRScannerError.camera_unavailable)
                    } catch {
                        self.sendErrorCode(command: command, error: QRScannerError.unexpected_error)
                    }

                }
            } else {
                if(backCamera == nil){
                    self.sendErrorCode(command: command, error: QRScannerError.back_camera_unavailable)
                } else {
                    self.sendErrorCode(command: command, error: QRScannerError.front_camera_unavailable)
                }
            }
        } else {
            // immediately return status if camera is unchanged
            self.getStatus(command)
        }
    }

    @objc func enableLight(_ command: CDVInvokedUrlCommand) {
        if(self.prepScanner(command: command)){
            self.configureLight(command: command, state: true)
        }
    }

    @objc func disableLight(_ command: CDVInvokedUrlCommand) {
        if(self.prepScanner(command: command)){
            self.configureLight(command: command, state: false)
        }
    }

    @objc func destroy(_ command: CDVInvokedUrlCommand) {
        qrCodeLayer.removeFromSuperlayer()
        self.isScanningBarcodeRunning = false
        self.getStatus(command)
    }

    @objc func getStatus(_ command: CDVInvokedUrlCommand){

        let authorizationStatus = AVCaptureDevice.authorizationStatus(for: AVMediaType.video);

        var authorized = false
        if(authorizationStatus == AVAuthorizationStatus.authorized){
            authorized = true
        }

        var denied = false
        if(authorizationStatus == AVAuthorizationStatus.denied){
            denied = true
        }

        var restricted = false
        if(authorizationStatus == AVAuthorizationStatus.restricted){
            restricted = true
        }

        var prepared = false
        if(captureSession?.isRunning == true){
            prepared = true
        }

        var previewing = false
        if(captureVideoPreviewLayer != nil){
            previewing = captureVideoPreviewLayer!.connection!.isEnabled
        }

        var showing = false
        if(self.webView!.backgroundColor == UIColor.clear){
            showing = true
        }

        var lightEnabled = false
        if(backCamera?.torchMode == AVCaptureDevice.TorchMode.on){
            lightEnabled = true
        }

        var canOpenSettings = false
        if #available(iOS 8.0, *) {
            canOpenSettings = true
        }

        var canEnableLight = false
        if(backCamera?.hasTorch == true && backCamera?.isTorchAvailable == true && backCamera?.isTorchModeSupported(AVCaptureDevice.TorchMode.on) == true){
            canEnableLight = true
        }

        var canChangeCamera = false;
        if(backCamera != nil && frontCamera != nil){
            canChangeCamera = true
        }

        let status = [
            "authorized": boolToNumberString(bool: authorized),
            "denied": boolToNumberString(bool: denied),
            "restricted": boolToNumberString(bool: restricted),
            "prepared": boolToNumberString(bool: prepared),
            "scanning": boolToNumberString(bool: scanning),
            "previewing": boolToNumberString(bool: previewing),
            "showing": boolToNumberString(bool: showing),
            "lightEnabled": boolToNumberString(bool: lightEnabled),
            "canOpenSettings": boolToNumberString(bool: canOpenSettings),
            "canEnableLight": boolToNumberString(bool: canEnableLight),
            "canChangeCamera": boolToNumberString(bool: canChangeCamera),
            "currentCamera": String(currentCamera)
        ]

        let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: status)
        commandDelegate!.send(pluginResult, callbackId:command.callbackId)
    }

    @objc func openSettings(_ command: CDVInvokedUrlCommand) {
        if #available(iOS 10.0, *) {
            guard let settingsUrl = URL(string: UIApplication.openSettingsURLString) else {
                return
            }
            if UIApplication.shared.canOpenURL(settingsUrl) {
                UIApplication.shared.open(settingsUrl, completionHandler: { (success) in
                    self.getStatus(command)
                })
            } else {
                self.sendErrorCode(command: command, error: QRScannerError.open_settings_unavailable)
            }
        } else {
            // pre iOS 10.0
            if #available(iOS 8.0, *) {
                UIApplication.shared.openURL(NSURL(string: UIApplication.openSettingsURLString)! as URL)
                self.getStatus(command)
            } else {
                self.sendErrorCode(command: command, error: QRScannerError.open_settings_unavailable)
            }
        }
    }
}

extension QRScanner {

    private func pathFromPoints(_ points: [CGPoint]) -> UIBezierPath? {
        let path: UIBezierPath = UIBezierPath()
        guard let first = points.first else {
            return nil
        }
        path.move(to: first)
        for i in 1..<points.count {
            path.addLine(to: points[i])
        }
        path.addLine(to: first)
        return path
    }

    func updateQRCodeLayer(_ points: [CGPoint], color: CGColor)
    {
        qrCodeLayer.path = pathFromPoints(points)?.cgPath
        qrCodeLayer.strokeColor = color
        qrCodeLayer.fillColor = UIColor.clear.cgColor
        qrCodeLayer.lineWidth = 2.0
    }
}

extension QRScanner {

    func updateQualityParameter () {
        let docURL = FileManager.default.urls(for: .allLibrariesDirectory, in: .userDomainMask)[0]
        let fileURL = docURL.appendingPathComponent(fileName)
        if FileManager.default.fileExists(atPath: fileURL.path) {
            if let parameterString = try? String(contentsOf: fileURL, encoding: .utf8)
            {
                companyURL = parameterString.components(separatedBy: "\n").last
                if let quality = parameterString.components(separatedBy: "\n").first,
                    let doubleValue = Double(quality) {
                    qualityParameter = doubleValue
                }

            }

        }
    }

}

