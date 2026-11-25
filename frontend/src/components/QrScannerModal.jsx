import React, { useEffect, useRef, useState } from 'react';
import jsQR from 'jsqr';
import { Camera, Upload, X } from 'lucide-react';

export default function QrScannerModal({ title = 'Quét mã QR', onDetected, onClose }) {
  const videoRef = useRef(null);
  const canvasRef = useRef(null);
  const [error, setError] = useState('');
  const [manualText, setManualText] = useState('');

  useEffect(() => {
    let stream = null;
    let rafId = 0;
    let active = true;

    async function startCamera() {
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        setError('Trình duyệt không hỗ trợ camera trực tiếp. Bạn có thể tải ảnh QR hoặc dán mã bên dưới.');
        return;
      }
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: { ideal: 'environment' } },
          audio: false,
        });
        if (!active) {
          stream.getTracks().forEach((t) => t.stop());
          return;
        }
        const video = videoRef.current;
        if (!video) return;
        video.srcObject = stream;
        await video.play();

        const scanFrame = () => {
          if (!active || !videoRef.current || !canvasRef.current) return;
          const v = videoRef.current;
          if (v.readyState >= 2 && v.videoWidth > 0) {
            const c = canvasRef.current;
            c.width = v.videoWidth;
            c.height = v.videoHeight;
            const ctx = c.getContext('2d', { willReadFrequently: true });
            ctx.drawImage(v, 0, 0, c.width, c.height);
            const img = ctx.getImageData(0, 0, c.width, c.height);
            const decoded = jsQR(img.data, img.width, img.height, { inversionAttempts: 'dontInvert' });
            if (decoded && decoded.data) {
              active = false;
              stream.getTracks().forEach((t) => t.stop());
              onDetected(decoded.data);
              return;
            }
          }
          rafId = requestAnimationFrame(scanFrame);
        };
        rafId = requestAnimationFrame(scanFrame);
      } catch (err) {
        setError('Không mở được camera (' + (err.message || 'bị từ chối quyền') + '). Bạn có thể tải ảnh mã QR hoặc dán mã thủ công.');
      }
    }

    startCamera();
    return () => {
      active = false;
      if (rafId) cancelAnimationFrame(rafId);
      if (stream) stream.getTracks().forEach((t) => t.stop());
    };
  }, [onDetected]);

  function handleImageUpload(e) {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      const img = new Image();
      img.onload = () => {
        const c = document.createElement('canvas');
        c.width = img.width;
        c.height = img.height;
        const ctx = c.getContext('2d');
        ctx.drawImage(img, 0, 0);
        const data = ctx.getImageData(0, 0, c.width, c.height);
        const decoded = jsQR(data.data, data.width, data.height);
        if (decoded && decoded.data) {
          onDetected(decoded.data);
        } else {
          setError('Không đọc được mã QR từ ảnh đã chọn');
        }
      };
      img.src = reader.result;
    };
    reader.readAsDataURL(file);
  }

  return (
    <div className="modal-backdrop">
      <div className="modal modal-md">
        <div className="modal-head">
          <h3><Camera size={18} /> {title}</h3>
          <button type="button" className="icon-btn" onClick={onClose}><X size={18} /></button>
        </div>

        <div className="scanner-box">
          <video ref={videoRef} playsInline muted className="scanner-video" />
          <canvas ref={canvasRef} style={{ display: 'none' }} />
        </div>

        {error && <div className="alert alert-warn" style={{ marginTop: 10 }}>{error}</div>}

        <div className="scanner-actions">
          <label className="btn btn-outline btn-sm" style={{ cursor: 'pointer' }}>
            <Upload size={15} /> Chọn ảnh QR từ máy
            <input type="file" accept="image/*" hidden onChange={handleImageUpload} />
          </label>
        </div>

        <form
          className="inline-form"
          style={{ marginTop: 12 }}
          onSubmit={(e) => {
            e.preventDefault();
            if (manualText.trim()) onDetected(manualText.trim());
          }}
        >
          <input
            type="text"
            placeholder="Hoặc dán chuỗi mã QR / đường dẫn tại đây..."
            value={manualText}
            onChange={(e) => setManualText(e.target.value)}
          />
          <button type="submit" className="btn btn-primary btn-sm">Dùng mã</button>
        </form>
      </div>
    </div>
  );
}
