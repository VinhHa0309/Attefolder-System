import React, { useEffect, useState } from 'react';
import { QRCodeSVG } from 'qrcode.react';
import {
  apiFetch,
  downloadFile,
  FLAG_LABELS,
  formatCountdown,
  formatDateTime,
  RESULT_META,
  SESSION_STATUS_META,
} from '../api';
import MapPicker from './MapPicker';
import {
  ArrowLeft,
  Check,
  Clock,
  Copy,
  Download,
  FileSpreadsheet,
  MapPin,
  Play,
  Plus,
  QrCode,
  RefreshCw,
  Smartphone,
  StopCircle,
  Trash2,
  Upload,
  UserCheck,
  X,
} from 'lucide-react';

function OpenSessionModal({ classInfo, onOpened, onClose, toast }) {
  const [durationSeconds, setDurationSeconds] = useState(Number(classInfo.default_session_duration_s) || 300);
  const [radiusMeters, setRadiusMeters] = useState(Number(classInfo.radius_m) || 50);
  const [gpsAccuracyMeters, setGpsAccuracyMeters] = useState(Number(classInfo.gps_accuracy_m) || 30);
  const [centerMode, setCenterMode] = useState('PHONE_GPS');
  const [probe, setProbe] = useState(null);
  const [probeCaptured, setProbeCaptured] = useState(null);
  const [mapLat, setMapLat] = useState(null);
  const [mapLng, setMapLng] = useState(null);
  const [manualLat, setManualLat] = useState('');
  const [manualLng, setManualLng] = useState('');
  const [browserGps, setBrowserGps] = useState(null);
  const [busy, setBusy] = useState(false);

  const rMin = Number(classInfo.radius_min_m) || 10;
  const rMax = Number(classInfo.radius_max_m) || 300;
  const gMin = Number(classInfo.gps_accuracy_min_m) || 5;
  const gMax = Number(classInfo.gps_accuracy_max_m) || 150;

  async function initProbe() {
    try {
      setProbeCaptured(null);
      const res = await apiFetch('/sessions/center-probe', {
        method: 'POST',
        body: { classId: classInfo.id },
      });
      setProbe(res);
    } catch (err) {
      toast(err.message, 'err');
    }
  }

  useEffect(() => {
    if (centerMode === 'PHONE_GPS' && !probe) {
      initProbe();
    }
  }, [centerMode]);

  useEffect(() => {
    if (!probe?.probeToken || centerMode !== 'PHONE_GPS' || probeCaptured) return;
    const iv = setInterval(async () => {
      try {
        const data = await apiFetch(`/sessions/center-probe/${probe.probeToken}`);
        if (data.latitude != null && data.longitude != null) {
          setProbeCaptured({
            lat: Number(data.latitude),
            lng: Number(data.longitude),
            accuracy: data.accuracy_m != null ? Number(data.accuracy_m) : null,
            capturedAt: data.captured_at,
          });
        }
      } catch {
        // ignore transient poll error
      }
    }, 2000);
    return () => clearInterval(iv);
  }, [probe?.probeToken, centerMode, probeCaptured]);

  function captureDirectPhoneGps() {
    if (!navigator.geolocation) {
      toast('Trình duyệt không hỗ trợ GPS', 'err');
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setBrowserGps({
          lat: Number(pos.coords.latitude.toFixed(6)),
          lng: Number(pos.coords.longitude.toFixed(6)),
          accuracy: Math.round(pos.coords.accuracy || 0),
        });
        toast('Đã ghi nhận tọa độ GPS từ thiết bị hiện tại');
      },
      (err) => {
        toast('Không lấy được GPS (' + err.message + '). Vui lòng quét QR bằng điện thoại hoặc chọn trên bản đồ.', 'err');
      },
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 0 },
    );
  }

  async function handleStart(e) {
    e.preventDefault();
    let payload = {
      classId: classInfo.id,
      durationSeconds: Number(durationSeconds),
      radiusMeters: Number(radiusMeters),
      gpsAccuracyMeters: Number(gpsAccuracyMeters),
    };

    if (centerMode === 'PHONE_GPS') {
      if (probeCaptured) {
        payload.probeToken = probe.probeToken;
        payload.centerSource = 'PHONE_GPS';
      } else if (browserGps) {
        payload.centerLat = browserGps.lat;
        payload.centerLng = browserGps.lng;
        payload.centerAccuracyMeters = browserGps.accuracy;
        payload.centerSource = 'PHONE_GPS';
      } else {
        toast('Chưa có tọa độ GPS điện thoại. Hãy quét QR trên điện thoại hoặc chuyển sang tab Bản đồ số.', 'err');
        return;
      }
    } else {
      const latVal = mapLat ?? (manualLat ? Number(manualLat) : null);
      const lngVal = mapLng ?? (manualLng ? Number(manualLng) : null);
      if (latVal == null || lngVal == null || Number.isNaN(latVal) || Number.isNaN(lngVal)) {
        toast('Không xác định được tâm điểm: Vui lòng nhấp chọn tọa độ phòng học trên bản đồ số.', 'err');
        return;
      }
      payload.centerLat = latVal;
      payload.centerLng = lngVal;
      payload.centerSource = 'MAP';
    }

    setBusy(true);
    try {
      const session = await apiFetch('/sessions', { method: 'POST', body: payload });
      toast('Đã mở phiên điểm danh');
      onOpened(session);
    } catch (err) {
      toast(err.message, 'err');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop">
      <div className="modal modal-lg">
        <div className="modal-head">
          <h3><Play size={18} /> Mở phiên điểm danh · {classInfo.code}</h3>
          <button type="button" className="icon-btn" onClick={onClose}><X size={18} /></button>
        </div>

        <form onSubmit={handleStart} className="stack">
          <div className="grid-3">
            <label>
              Thời gian đếm ngược (giây)
              <input
                type="number"
                min={30}
                max={14400}
                value={durationSeconds}
                onChange={(e) => setDurationSeconds(e.target.value)}
                required
              />
              <span className="muted tiny">≈ {Math.round(durationSeconds / 60)} phút · Chu kỳ đổi mã: {classInfo.code_interval_seconds}s</span>
            </label>
            <label>
              Bán kính hợp lệ ({rMin}–{rMax}m)
              <input
                type="number"
                min={rMin}
                max={rMax}
                value={radiusMeters}
                onChange={(e) => setRadiusMeters(e.target.value)}
                required
              />
              <span className="muted tiny">Mặc định trường: {classInfo.radius_m}m</span>
            </label>
            <label>
              Ngưỡng sai số GPS ({gMin}–{gMax}m)
              <input
                type="number"
                min={gMin}
                max={gMax}
                value={gpsAccuracyMeters}
                onChange={(e) => setGpsAccuracyMeters(e.target.value)}
                required
              />
              <span className="muted tiny">Mặc định trường: {classInfo.gps_accuracy_m}m</span>
            </label>
          </div>

          <div className="section-box">
            <div className="pill-switch" style={{ marginBottom: 12 }}>
              <button type="button" className={centerMode === 'PHONE_GPS' ? 'active' : ''} onClick={() => setCenterMode('PHONE_GPS')}>
                <Smartphone size={14} /> Chốt tâm điểm bằng GPS điện thoại
              </button>
              <button type="button" className={centerMode === 'MAP' ? 'active' : ''} onClick={() => setCenterMode('MAP')}>
                <MapPin size={14} /> Chọn trên bản đồ số
              </button>
            </div>

            {centerMode === 'PHONE_GPS' ? (
              <div className="probe-grid">
                <div className="probe-qr">
                  {probe ? <QRCodeSVG value={probe.probeUrl} size={156} /> : <div className="muted">Đang tạo QR...</div>}
                </div>
                <div className="stack">
                  <p className="small">
                    Quét mã QR bên trái bằng điện thoại cá nhân đang đứng trong phòng học để gửi GPS chốt tâm điểm. Hoặc bấm trực tiếp nếu bạn đang thao tác trên điện thoại.
                  </p>
                  {probe && (
                    <div className="code-copy">
                      <code className="tiny">{probe.probeUrl}</code>
                      <button
                        type="button"
                        className="btn btn-outline btn-sm"
                        onClick={() => { navigator.clipboard.writeText(probe.probeUrl); toast('Đã sao chép liên kết chốt GPS'); }}
                      >
                        <Copy size={13} />
                      </button>
                    </div>
                  )}
                  <div className="row-actions">
                    <button type="button" className="btn btn-outline btn-sm" onClick={captureDirectPhoneGps}>
                      <MapPin size={14} /> Lấy GPS thiết bị này
                    </button>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={initProbe}>
                      <RefreshCw size={14} /> Tạo lại QR
                    </button>
                  </div>
                  {probeCaptured && (
                    <div className="alert alert-ok">
                      <Check size={15} /> Đã nhận GPS điện thoại qua QR: {probeCaptured.lat}, {probeCaptured.lng} (sai số ~{probeCaptured.accuracy ?? '?'}m)
                    </div>
                  )}
                  {!probeCaptured && browserGps && (
                    <div className="alert alert-ok">
                      <Check size={15} /> Đã lấy GPS thiết bị: {browserGps.lat}, {browserGps.lng} (sai số ~{browserGps.accuracy}m)
                    </div>
                  )}
                  {!probeCaptured && !browserGps && (
                    <div className="alert alert-warn">Đang chờ tín hiệu GPS... Nếu chưa có GPS và chưa chọn bản đồ, phiên không thể mở.</div>
                  )}
                </div>
              </div>
            ) : (
              <div className="stack">
                <MapPicker
                  lat={mapLat}
                  lng={mapLng}
                  radiusMeters={radiusMeters}
                  onChange={(lat, lng) => {
                    setMapLat(lat);
                    setMapLng(lng);
                    setManualLat(String(lat));
                    setManualLng(String(lng));
                  }}
                />
                <div className="grid-2">
                  <label>
                    Vĩ độ (Latitude)
                    <input
                      type="number"
                      step="any"
                      placeholder="VD: 21.028511"
                      value={manualLat}
                      onChange={(e) => {
                        setManualLat(e.target.value);
                        const v = parseFloat(e.target.value);
                        setMapLat(Number.isNaN(v) ? null : v);
                      }}
                    />
                  </label>
                  <label>
                    Kinh độ (Longitude)
                    <input
                      type="number"
                      step="any"
                      placeholder="VD: 105.854198"
                      value={manualLng}
                      onChange={(e) => {
                        setManualLng(e.target.value);
                        const v = parseFloat(e.target.value);
                        setMapLng(Number.isNaN(v) ? null : v);
                      }}
                    />
                  </label>
                </div>
              </div>
            )}
          </div>

          <div className="row-between">
            <button type="button" className="btn btn-outline" onClick={onClose}>Hủy</button>
            <button type="submit" className="btn btn-primary" disabled={busy}>
              {busy ? 'Đang mở phiên...' : 'Bắt đầu phiên điểm danh'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

function SessionWorkspace({ sessionId, onBack, toast }) {
  const [data, setData] = useState(null);
  const [code, setCode] = useState(null);
  const [nowMs, setNowMs] = useState(Date.now());
  const [serverOffsetMs, setServerOffsetMs] = useState(0);
  const [recordTab, setRecordTab] = useState('records');
  const [busy, setBusy] = useState(false);

  async function loadRecords() {
    try {
      const res = await apiFetch(`/sessions/${sessionId}/records`);
      if (res.serverNow) {
        setServerOffsetMs(new Date(res.serverNow).getTime() - Date.now());
      }
      setData(res);
    } catch (err) {
      toast(err.message, 'err');
    }
  }

  async function loadCode() {
    try {
      const c = await apiFetch(`/sessions/${sessionId}/code`);
      if (c.serverNow) {
        setServerOffsetMs(new Date(c.serverNow).getTime() - Date.now());
      }
      setCode(c);
    } catch {
      // Session may have just expired or closed on server; refresh immediately
      loadRecords();
    }
  }

  useEffect(() => {
    loadRecords();
    const tick = setInterval(() => setNowMs(Date.now()), 1000);
    const poll = setInterval(() => {
      loadRecords();
    }, 3000);
    return () => {
      clearInterval(tick);
      clearInterval(poll);
    };
  }, [sessionId]);

  const session = data?.session;
  useEffect(() => {
    if (session?.status !== 'OPEN') {
      setCode(null);
      return;
    }
    loadCode();
    const codeTimer = setInterval(loadCode, 1000);
    return () => clearInterval(codeTimer);
  }, [session?.status, sessionId]);

  if (!data || !session) {
    return <div className="card">Đang tải dữ liệu phiên điểm danh...</div>;
  }

  const records = data.records || [];
  const unrecorded = data.unrecordedStudents || [];
  const audits = data.audits || [];

  const counts = records.reduce(
    (acc, r) => {
      acc[r.result] = (acc[r.result] || 0) + 1;
      return acc;
    },
    { PRESENT: 0, REVIEW: 0, PENDING: 0, ABSENT: 0 },
  );

  const serverNowMs = nowMs + serverOffsetMs;
  const endsLeft = session.ends_at ? (new Date(session.ends_at).getTime() - serverNowMs) / 1000 : 0;
  const codeLeft = code?.validUntil ? (new Date(code.validUntil).getTime() - serverNowMs) / 1000 : 0;
  const statusMeta = SESSION_STATUS_META[session.status] || { label: session.status, cls: 'badge-slate' };

  async function closeNow() {
    if (!confirm('Đóng phiên điểm danh sớm và chạy phân tích tương quan vị trí (MAD)?')) return;
    setBusy(true);
    try {
      const res = await apiFetch(`/sessions/${sessionId}/close`, { method: 'POST' });
      toast(res.summary || 'Đã đóng phiên và chuyển sang bước đối soát');
      await loadRecords();
    } catch (err) {
      toast(err.message, 'err');
    } finally {
      setBusy(false);
    }
  }

  async function finalizeNow() {
    if (!confirm('Chốt kết quả buổi học? Sau khi chốt, sinh viên chưa có bản ghi thành Vắng mặt và không thể dùng Duyệt/Thủ công nữa.')) return;
    setBusy(true);
    try {
      await apiFetch(`/sessions/${sessionId}/finalize`, { method: 'POST' });
      toast('Đã chốt kết quả buổi học thành công');
      await loadRecords();
    } catch (err) {
      toast(err.message, 'err');
    } finally {
      setBusy(false);
    }
  }

  async function reviewRecord(rec, decision) {
    try {
      const res = await apiFetch(`/sessions/${sessionId}/records/${rec.id}/review`, {
        method: 'POST',
        body: { decision },
      });
      toast(
        decision === 'approve'
          ? (res.deviceUpdated ? `Đã phê duyệt ${rec.student_no} và cập nhật thiết bị gốc mới` : `Đã phê duyệt ${rec.student_no} Có mặt`)
          : `Đã từ chối ${rec.student_no} (Vắng mặt)`,
      );
      await loadRecords();
    } catch (err) {
      toast(err.message, 'err');
    }
  }

  async function markManual(student, result) {
    try {
      await apiFetch(`/sessions/${sessionId}/records/manual`, {
        method: 'POST',
        body: { studentId: student.student_id, result },
      });
      toast(`Đã ghi nhận thủ công ${student.student_no}: ${result === 'PRESENT' ? 'Có mặt' : 'Vắng mặt'}`);
      await loadRecords();
    } catch (err) {
      toast(err.message, 'err');
    }
  }

  async function amendFinalized(rec, decision) {
    const reason = prompt(`Nhập lý do chỉnh sửa kết quả sau khi chốt cho ${rec.full_name} (${rec.student_no}):`);
    if (!reason || !reason.trim()) return;
    try {
      await apiFetch(`/sessions/${sessionId}/records/${rec.id}/finalized-result`, {
        method: 'PATCH',
        body: { decision, reason: reason.trim() },
      });
      toast(`Đã chỉnh sửa kết quả cho ${rec.student_no}`);
      await loadRecords();
    } catch (err) {
      toast(err.message, 'err');
    }
  }

  return (
    <div className="stack-lg">
      <div className="card-head">
        <div className="row-actions">
          <button type="button" className="btn btn-outline btn-sm" onClick={onBack}><ArrowLeft size={15} /> Về lớp học phần</button>
          <div>
            <h2>{session.class_name} ({session.class_code})</h2>
            <div className="muted small">
              Mở lúc {formatDateTime(session.started_at)} · Nguồn tâm điểm: <strong>{session.center_source === 'PHONE_GPS' ? 'GPS điện thoại' : 'Bản đồ số'}</strong> · Bán kính {session.radius_m}m
            </div>
          </div>
        </div>
        <div className="row-actions">
          <span className={`badge ${statusMeta.cls}`}>{statusMeta.label}</span>
          {session.status === 'OPEN' && (
            <button type="button" className="btn btn-danger" onClick={closeNow} disabled={busy}>
              <StopCircle size={15} /> Đóng phiên điểm danh
            </button>
          )}
          {session.status === 'RECONCILING' && (
            <button type="button" className="btn btn-primary" onClick={finalizeNow} disabled={busy}>
              <Check size={15} /> Chốt kết quả buổi học
            </button>
          )}
        </div>
      </div>

      {session.status === 'OPEN' && (
        <div className="projector-card">
          <div className="projector-qr">
            {code ? <QRCodeSVG value={code.qrToken} size={230} /> : <div>Đang sinh mã QR...</div>}
          </div>
          <div className="projector-info">
            <div className="muted small">MÃ PIN ĐIỂM DANH (LÀM MỚI MỖI {session.code_interval_seconds}s)</div>
            <div className="pin-big">{code ? code.pin : '------'}</div>
            <div className="countdown-row">
              <div><Clock size={15} /> Đổi mã sau: <strong>{formatCountdown(codeLeft)}</strong></div>
              <div>Phiên kết thúc sau: <strong>{formatCountdown(endsLeft)}</strong></div>
            </div>
            <p className="muted small">
              Chiếu màn hình này để sinh viên quét mã QR hoặc nhập PIN trên thiết bị cá nhân.
            </p>
          </div>
        </div>
      )}

      {session.reconcile_outcome && (
        <div className={`alert ${session.center_shifted ? 'alert-warn' : session.reconcile_outcome === 'CENTER_SUSPECT' ? 'alert-err' : 'alert-info'}`}>
          <strong>Phân tích tương quan vị trí (MAD): {session.reconcile_outcome}</strong>
          {session.reconcile_mad_m != null && <span> · MAD = {Number(session.reconcile_mad_m).toFixed(1)}m</span>}
          {session.center_shifted && <div>Hệ thống đã tự động dịch tâm điểm về tâm cụm hợp lệ (tâm cũ từ bản đồ số) và gắn cờ "Tâm điểm đã được hệ thống dịch".</div>}
          {session.reconcile_outcome === 'CENTER_SUSPECT' && <div>Phát hiện cụm chặt nằm ngoài bán kính GPS điện thoại — giữ ở trạng thái Chờ duyệt kèm cờ Nghi vấn tâm điểm lệch để Giảng viên xác nhận.</div>}
          {session.status === 'RECONCILING' && session.reconcile_until && (
            <div className="small">Tự động chốt nếu quá hạn đối soát lúc: {formatDateTime(session.reconcile_until)}</div>
          )}
        </div>
      )}

      <div className="kpi-row">
        <div className="kpi kpi-green"><span>Có mặt</span><strong>{counts.PRESENT}</strong></div>
        <div className="kpi kpi-amber"><span>Chờ đối chiếu</span><strong>{counts.REVIEW}</strong></div>
        <div className="kpi kpi-orange"><span>Chờ duyệt</span><strong>{counts.PENDING}</strong></div>
        <div className="kpi kpi-red"><span>Vắng mặt</span><strong>{counts.ABSENT}</strong></div>
        <div className="kpi"><span>Chưa điểm danh (Whitelist)</span><strong>{unrecorded.length}</strong></div>
      </div>

      <div className="tab-bar">
        <button type="button" className={recordTab === 'records' ? 'tab active' : 'tab'} onClick={() => setRecordTab('records')}>
          Bản ghi điểm danh ({records.length}) — sắp xếp độ lệch giảm dần
        </button>
        {session.status !== 'FINALIZED' && (
          <button type="button" className={recordTab === 'manual' ? 'tab active' : 'tab'} onClick={() => setRecordTab('manual')}>
            <UserCheck size={14} /> Whitelist chưa có bản ghi ({unrecorded.length})
          </button>
        )}
        <button type="button" className={recordTab === 'audit' ? 'tab active' : 'tab'} onClick={() => setRecordTab('audit')}>
          Nhật ký kiểm toán ({audits.length})
        </button>
      </div>

      {recordTab === 'records' && (
        <div className="card">
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>MSSV</th>
                  <th>Họ tên</th>
                  <th>Trạng thái</th>
                  <th>Lệch (m)</th>
                  <th>Sai số GPS</th>
                  <th>IP</th>
                  <th>Cờ cảnh báo & Chi tiết</th>
                  <th>Thao tác</th>
                </tr>
              </thead>
              <tbody>
                {records.map((r) => {
                  const rm = RESULT_META[r.result] || { label: r.result, cls: 'badge-slate' };
                  const flags = Array.isArray(r.flags) ? r.flags : [];
                  return (
                    <tr key={r.id}>
                      <td><strong>{r.student_no}</strong></td>
                      <td>{r.full_name}</td>
                      <td><span className={`badge ${rm.cls}`}>{rm.label}</span></td>
                      <td>{r.distance_m != null ? `${Math.round(r.distance_m)}m` : '—'}</td>
                      <td>{r.gps_accuracy_m != null ? `${Math.round(r.gps_accuracy_m)}m` : '—'}</td>
                      <td>
                        <div className="tiny">{r.client_ip || '—'}</div>
                        {r.ip_in_school && <span className="badge badge-green tiny">IP trường</span>}
                      </td>
                      <td>
                        <div className="flag-list">
                          {flags.map((f) => (
                            <span key={f} className="badge badge-warn tiny">{FLAG_LABELS[f] || f}</span>
                          ))}
                        </div>
                        <div className="muted tiny">{r.message}</div>
                        {r.reviewed_at && (
                          <div className="muted tiny">
                            Xử lý/sửa: {r.reviewed_by_name || 'Hệ thống'} · {formatDateTime(r.reviewed_at)}
                          </div>
                        )}
                      </td>
                      <td>
                        {session.status !== 'FINALIZED' ? (
                          <div className="row-actions">
                            <button type="button" className="btn btn-primary btn-sm" onClick={() => reviewRecord(r, 'approve')}>
                              Phê duyệt
                            </button>
                            <button type="button" className="btn btn-outline btn-sm" onClick={() => reviewRecord(r, 'reject')}>
                              Từ chối
                            </button>
                          </div>
                        ) : (
                          <div className="row-actions">
                            <button type="button" className="btn btn-outline btn-sm" onClick={() => amendFinalized(r, 'approve')}>
                              Sửa Có mặt
                            </button>
                            <button type="button" className="btn btn-outline btn-sm" onClick={() => amendFinalized(r, 'reject')}>
                              Sửa Vắng
                            </button>
                          </div>
                        )}
                      </td>
                    </tr>
                  );
                })}
                {records.length === 0 && <tr><td colSpan={8} className="muted">Chưa có sinh viên nào quét điểm danh.</td></tr>}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {recordTab === 'manual' && session.status !== 'FINALIZED' && (
        <div className="card">
          <p className="muted small">
            Sinh viên nằm trong Danh sách Whitelist nhưng chưa có bản ghi (hết pin, từ chối vượt ngưỡng lệch tối đa...). Bạn có thể điểm danh thủ công nếu sinh viên thực sự có mặt.
          </p>
          <div className="table-wrap" style={{ marginTop: 10 }}>
            <table>
              <thead>
                <tr><th>MSSV</th><th>Họ tên</th><th>Tài khoản</th><th>Thao tác thủ công</th></tr>
              </thead>
              <tbody>
                {unrecorded.map((u) => (
                  <tr key={u.student_no}>
                    <td><strong>{u.student_no}</strong></td>
                    <td>{u.full_name}</td>
                    <td>{u.has_account ? u.account_email : <span className="muted">Chưa cấp TK ở trường</span>}</td>
                    <td>
                      {u.has_account ? (
                        <div className="row-actions">
                          <button type="button" className="btn btn-primary btn-sm" onClick={() => markManual(u, 'PRESENT')}>Có mặt</button>
                          <button type="button" className="btn btn-outline btn-sm" onClick={() => markManual(u, 'ABSENT')}>Vắng mặt</button>
                        </div>
                      ) : (
                        <span className="muted tiny">Admin trường cần nạp tài khoản</span>
                      )}
                    </td>
                  </tr>
                ))}
                {unrecorded.length === 0 && <tr><td colSpan={4} className="muted">Tất cả sinh viên trong Whitelist đã có bản ghi.</td></tr>}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {recordTab === 'audit' && (
        <div className="card">
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>Thời gian</th><th>Hành động</th><th>Người thực hiện</th><th>Trước</th><th>Sau</th><th>Lý do</th></tr>
              </thead>
              <tbody>
                {audits.map((a) => (
                  <tr key={a.id}>
                    <td>{formatDateTime(a.created_at)}</td>
                    <td><code>{a.action}</code></td>
                    <td>{a.actor_name}</td>
                    <td><code className="tiny">{a.before_value || '—'}</code></td>
                    <td><code className="tiny">{a.after_value || '—'}</code></td>
                    <td>{a.reason || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}

function ClassDetailView({ classId, onBack, onOpenSessionWorkspace, toast }) {
  const [detail, setDetail] = useState(null);
  const [tab, setTab] = useState('sessions');
  const [showOpenModal, setShowOpenModal] = useState(false);
  const [showQrZoom, setShowQrZoom] = useState(false);
  const [tplForm, setTplForm] = useState({ name: '', startRow: 2, mssvCol: 'A', firstAttendanceCol: 'C', presentSymbol: 'P' });
  const [tplFile, setTplFile] = useState(null);
  const [selectedTplId, setSelectedTplId] = useState('');
  const [busy, setBusy] = useState(false);

  async function load() {
    const res = await apiFetch(`/classes/${classId}`);
    setDetail(res);
  }

  useEffect(() => {
    load().catch((e) => toast(e.message, 'err'));
  }, [classId]);

  if (!detail) return <div className="card">Đang tải thông tin lớp học phần...</div>;
  const { classInfo, whitelist = [], sessions = [], templates = [] } = detail;
  const staticJoinUrl = `${window.location.origin}/?join=${classInfo.static_qr_token}`;

  async function uploadWhitelist(e, replace = false) {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    const fd = new FormData();
    fd.append('file', file);
    try {
      const res = await apiFetch(`/classes/${classId}/students/import?replace=${replace}`, { method: 'POST', formData: fd });
      toast(`Đã thêm ${res.added} MSSV vào Whitelist (Tổng: ${res.totalWhitelist})`);
      await load();
    } catch (err) {
      toast(err.message, 'err');
    }
  }

  async function removeWhitelistRow(studentNo) {
    try {
      await apiFetch(`/classes/${classId}/students/${encodeURIComponent(studentNo)}`, { method: 'DELETE' });
      toast(`Đã xóa ${studentNo} khỏi Whitelist`);
      await load();
    } catch (err) {
      toast(err.message, 'err');
    }
  }

  async function clearAllWhitelist() {
    if (!confirm('Xóa toàn bộ danh sách Whitelist hiện tại của lớp để nạp lại?')) return;
    try {
      await apiFetch(`/classes/${classId}/students`, { method: 'DELETE' });
      toast('Đã xóa toàn bộ Whitelist lớp');
      await load();
    } catch (err) {
      toast(err.message, 'err');
    }
  }

  async function uploadTemplate(e) {
    e.preventDefault();
    if (!tplFile) {
      toast('Vui lòng chọn file Excel mẫu của trường', 'err');
      return;
    }
    const fd = new FormData();
    fd.append('name', tplForm.name);
    fd.append('startRow', String(tplForm.startRow));
    fd.append('mssvCol', tplForm.mssvCol);
    fd.append('firstAttendanceCol', tplForm.firstAttendanceCol);
    fd.append('presentSymbol', tplForm.presentSymbol);
    fd.append('file', tplFile);
    setBusy(true);
    try {
      await apiFetch(`/classes/${classId}/templates`, { method: 'POST', formData: fd });
      setTplForm({ name: '', startRow: 2, mssvCol: 'A', firstAttendanceCol: 'C', presentSymbol: 'P' });
      setTplFile(null);
      await load();
      toast('Đã tải lên mẫu báo cáo Excel');
    } catch (err) {
      toast(err.message, 'err');
    } finally {
      setBusy(false);
    }
  }

  async function removeTemplate(id) {
    if (!confirm('Xóa mẫu báo cáo này?')) return;
    try {
      await apiFetch(`/classes/${classId}/templates/${id}`, { method: 'DELETE' });
      await load();
      toast('Đã xóa mẫu báo cáo');
    } catch (err) {
      toast(err.message, 'err');
    }
  }

  function exportReport(tplId) {
    const q = tplId ? `?templateId=${encodeURIComponent(tplId)}` : '';
    downloadFile(`/classes/${classId}/attendance.xlsx${q}`, `${classInfo.code}-attendance.xlsx`)
      .then(() => toast('Đang tải file báo cáo chuyên cần'))
      .catch((e) => toast(e.message, 'err'));
  }

  return (
    <div className="stack-lg">
      <div className="card">
        <div className="card-head">
          <div className="row-actions">
            <button type="button" className="btn btn-outline btn-sm" onClick={onBack}><ArrowLeft size={15} /> Tất cả lớp</button>
            <div>
              <h2>{classInfo.name} · <code>{classInfo.code}</code></h2>
              <div className="muted small">
                {classInfo.school_name} ({classInfo.school_code}) · {classInfo.term || '—'} · {classInfo.school_year || '—'}
              </div>
            </div>
          </div>
          <div className="row-actions">
            <button type="button" className="btn btn-outline" onClick={() => setShowQrZoom(true)}>
              <QrCode size={15} /> QR tĩnh tham gia lớp
            </button>
            <button type="button" className="btn btn-primary" onClick={() => setShowOpenModal(true)}>
              <Play size={15} /> Mở phiên điểm danh
            </button>
          </div>
        </div>

        <div className="export-bar">
          <div className="row-actions">
            <select value={selectedTplId} onChange={(e) => setSelectedTplId(e.target.value)}>
              <option value="">Xuất Excel chuẩn hệ thống (mặc định)</option>
              {templates.map((t) => (
                <option key={t.id} value={t.id}>Mẫu: {t.name} (Dòng {t.start_row}, Ký hiệu "{t.present_symbol}")</option>
              ))}
            </select>
            <button type="button" className="btn btn-outline" onClick={() => exportReport(selectedTplId)}>
              <FileSpreadsheet size={15} /> Xuất báo cáo Excel
            </button>
          </div>
        </div>
      </div>

      <div className="tab-bar">
        <button type="button" className={tab === 'sessions' ? 'tab active' : 'tab'} onClick={() => setTab('sessions')}>
          Phiên điểm danh ({sessions.length})
        </button>
        <button type="button" className={tab === 'whitelist' ? 'tab active' : 'tab'} onClick={() => setTab('whitelist')}>
          Whitelist & Ghi danh ({whitelist.length})
        </button>
        <button type="button" className={tab === 'templates' ? 'tab active' : 'tab'} onClick={() => setTab('templates')}>
          Mẫu báo cáo Excel ({templates.length})
        </button>
      </div>

      {tab === 'sessions' && (
        <div className="card">
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Thời gian mở</th>
                  <th>Trạng thái</th>
                  <th>Tâm điểm</th>
                  <th>Có mặt</th>
                  <th>Chờ đối chiếu</th>
                  <th>Chờ duyệt</th>
                  <th>Vắng</th>
                  <th>Mở</th>
                </tr>
              </thead>
              <tbody>
                {sessions.map((s) => {
                  const meta = SESSION_STATUS_META[s.status] || { label: s.status, cls: 'badge-slate' };
                  return (
                    <tr key={s.id}>
                      <td>{formatDateTime(s.started_at)}</td>
                      <td><span className={`badge ${meta.cls}`}>{meta.label}</span></td>
                      <td>{s.center_source === 'PHONE_GPS' ? 'GPS điện thoại' : 'Bản đồ số'}{s.center_shifted ? ' (Đã dịch)' : ''}</td>
                      <td>{s.present_count}</td>
                      <td>{s.review_count}</td>
                      <td>{s.pending_count}</td>
                      <td>{s.absent_count}</td>
                      <td>
                        <button type="button" className="btn btn-primary btn-sm" onClick={() => onOpenSessionWorkspace(s.id)}>
                          {s.status === 'OPEN' ? 'Chiếu & Quản lý' : s.status === 'RECONCILING' ? 'Đối soát / Chốt' : 'Xem & Chỉnh sửa'}
                        </button>
                      </td>
                    </tr>
                  );
                })}
                {sessions.length === 0 && <tr><td colSpan={8} className="muted">Chưa có phiên điểm danh nào.</td></tr>}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {tab === 'whitelist' && (
        <div className="card stack">
          <div className="row-between">
            <div className="row-actions">
              <button
                type="button"
                className="btn btn-outline btn-sm"
                onClick={() => downloadFile(`/classes/${classId}/students/template.xlsx`, 'class-roster-template.xlsx')}
              >
                <Download size={14} /> Tải mẫu Excel MSSV
              </button>
              <label className="btn btn-primary btn-sm" style={{ cursor: 'pointer' }}>
                <Upload size={14} /> Tải lên Excel (bổ sung)
                <input type="file" accept=".xlsx,.xls" hidden onChange={(e) => uploadWhitelist(e, false)} />
              </label>
              <label className="btn btn-outline btn-sm" style={{ cursor: 'pointer' }}>
                <Upload size={14} /> Nạp thay thế Whitelist
                <input type="file" accept=".xlsx,.xls" hidden onChange={(e) => uploadWhitelist(e, true)} />
              </label>
            </div>
            {whitelist.length > 0 && (
              <button type="button" className="btn btn-outline btn-sm" onClick={clearAllWhitelist}>
                <Trash2 size={14} /> Xóa toàn bộ Whitelist
              </button>
            )}
          </div>
          <p className="muted tiny">
            Hệ thống tự bỏ qua dòng tiêu đề trường/STT ở đầu file và trích đúng cột <strong>MSSV / Mã SV</strong>.
          </p>
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>MSSV (Whitelist)</th><th>Họ tên định danh</th><th>Email</th><th>Tài khoản</th><th>Liên kết lớp (Enrollment)</th><th></th></tr>
              </thead>
              <tbody>
                {whitelist.map((w) => (
                  <tr key={w.student_no}>
                    <td><strong>{w.student_no}</strong></td>
                    <td>{w.full_name || '—'}</td>
                    <td>{w.email || '—'}</td>
                    <td>{w.student_id ? <span className="badge badge-green">Đã cấp</span> : <span className="badge badge-slate">Chưa tạo TK</span>}</td>
                    <td>{w.enrolled_at ? `Đã liên kết (${formatDateTime(w.enrolled_at)})` : 'Chưa liên kết'}</td>
                    <td>
                      <button type="button" className="btn btn-ghost btn-sm" onClick={() => removeWhitelistRow(w.student_no)} title="Xóa khỏi Whitelist">
                        <Trash2 size={14} />
                      </button>
                    </td>
                  </tr>
                ))}
                {whitelist.length === 0 && <tr><td colSpan={6} className="muted">Danh sách Whitelist đang trống.</td></tr>}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {tab === 'templates' && (
        <div className="stack-lg">
          <div className="card">
            <h3>Tải lên file Excel mẫu của trường (Xuất theo mẫu)</h3>
            <p className="muted small">
              Hệ thống điền ký hiệu Có mặt vào các cột từ buổi đã chốt theo thời gian; ô trống là Vắng mặt và giữ nguyên định dạng bảng tính gốc.
            </p>
            <form onSubmit={uploadTemplate} className="grid-3" style={{ marginTop: 12 }}>
              <label>
                Tên mẫu báo cáo
                <input value={tplForm.name} onChange={(e) => setTplForm({ ...tplForm, name: e.target.value })} placeholder="VD: Bảng điểm danh Khoa CNTT" required />
              </label>
              <label>
                Dòng bắt đầu dữ liệu (từ 1)
                <input type="number" min={1} value={tplForm.startRow} onChange={(e) => setTplForm({ ...tplForm, startRow: e.target.value })} required />
              </label>
              <label>
                Ký hiệu Có mặt
                <input value={tplForm.presentSymbol} onChange={(e) => setTplForm({ ...tplForm, presentSymbol: e.target.value })} maxLength={10} required />
              </label>
              <label>
                Cột MSSV (VD: B hoặc 2)
                <input value={tplForm.mssvCol} onChange={(e) => setTplForm({ ...tplForm, mssvCol: e.target.value })} required />
              </label>
              <label>
                Cột điểm danh đầu tiên (VD: D hoặc 4)
                <input value={tplForm.firstAttendanceCol} onChange={(e) => setTplForm({ ...tplForm, firstAttendanceCol: e.target.value })} required />
              </label>
              <label>
                Tệp Excel mẫu (.xlsx / .xls)
                <input type="file" accept=".xlsx,.xls" onChange={(e) => setTplFile(e.target.files?.[0] || null)} required />
              </label>
              <div style={{ gridColumn: '1 / -1' }}>
                <button type="submit" className="btn btn-primary" disabled={busy}>Lưu mẫu báo cáo</button>
              </div>
            </form>
          </div>

          <div className="card">
            <h3>Danh sách mẫu đã lưu</h3>
            <div className="table-wrap" style={{ marginTop: 10 }}>
              <table>
                <thead>
                  <tr><th>Tên mẫu</th><th>File gốc</th><th>Dòng đầu</th><th>Cột MSSV / Điểm danh</th><th>Ký hiệu</th><th>Thao tác</th></tr>
                </thead>
                <tbody>
                  {templates.map((t) => (
                    <tr key={t.id}>
                      <td><strong>{t.name}</strong></td>
                      <td>{t.original_filename}</td>
                      <td>{t.start_row}</td>
                      <td>Cột {t.mssv_col} / Cột {t.first_attendance_col}+</td>
                      <td><code>{t.present_symbol}</code></td>
                      <td>
                        <div className="row-actions">
                          <button type="button" className="btn btn-primary btn-sm" onClick={() => exportReport(t.id)}>Xuất file</button>
                          <button type="button" className="btn btn-outline btn-sm" onClick={() => removeTemplate(t.id)}><Trash2 size={14} /></button>
                        </div>
                      </td>
                    </tr>
                  ))}
                  {templates.length === 0 && <tr><td colSpan={6} className="muted">Chưa có mẫu nào.</td></tr>}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {showOpenModal && (
        <OpenSessionModal
          classInfo={classInfo}
          onClose={() => setShowOpenModal(false)}
          onOpened={(sess) => {
            setShowOpenModal(false);
            onOpenSessionWorkspace(sess.id);
          }}
          toast={toast}
        />
      )}

      {showQrZoom && (
        <div className="modal-backdrop">
          <div className="modal modal-sm" style={{ textAlign: 'center' }}>
            <div className="modal-head">
              <h3>Mã QR tĩnh tham gia lớp · {classInfo.code}</h3>
              <button type="button" className="icon-btn" onClick={() => setShowQrZoom(false)}><X size={18} /></button>
            </div>
            <p className="muted small">Sinh viên trong Whitelist quét mã này để liên kết lớp vào danh sách môn học (chưa tính là có mặt).</p>
            <div style={{ padding: 16 }}>
              <QRCodeSVG value={staticJoinUrl} size={220} />
            </div>
            <div className="code-copy">
              <code className="tiny">{classInfo.static_qr_token}</code>
              <button
                type="button"
                className="btn btn-outline btn-sm"
                onClick={() => { navigator.clipboard.writeText(staticJoinUrl); toast('Đã sao chép liên kết tham gia lớp'); }}
              >
                <Copy size={13} /> Link
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default function LecturerView({ toast }) {
  const [classes, setClasses] = useState([]);
  const [schools, setSchools] = useState([]);
  const [selectedClassId, setSelectedClassId] = useState(null);
  const [activeSessionId, setActiveSessionId] = useState(null);
  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState({ schoolId: '', name: '', code: '', term: 'Học kỳ I', schoolYear: '2026–2027' });
  const [whitelistFile, setWhitelistFile] = useState(null);
  const [busy, setBusy] = useState(false);

  async function load() {
    const [cls, sch] = await Promise.all([apiFetch('/classes'), apiFetch('/public/schools')]);
    setClasses(cls);
    setSchools(sch);
    if (!form.schoolId && sch.length > 0) {
      setForm((f) => ({ ...f, schoolId: sch[0].id }));
    }
  }

  useEffect(() => {
    load().catch((e) => toast(e.message, 'err'));
  }, []);

  async function handleCreateClass(e) {
    e.preventDefault();
    setBusy(true);
    try {
      const fd = new FormData();
      fd.append('schoolId', form.schoolId);
      fd.append('name', form.name);
      fd.append('code', form.code);
      fd.append('term', form.term);
      fd.append('schoolYear', form.schoolYear);
      if (whitelistFile) fd.append('file', whitelistFile);
      const res = await apiFetch('/classes', { method: 'POST', formData: fd });
      setShowCreate(false);
      setForm({ schoolId: schools[0]?.id || '', name: '', code: '', term: 'Học kỳ I', schoolYear: '2026–2027' });
      setWhitelistFile(null);
      await load();
      toast(`Đã tạo lớp ${res.code} (Whitelist: ${res.whitelistCount} MSSV)`);
    } catch (err) {
      toast(err.message, 'err');
    } finally {
      setBusy(false);
    }
  }

  if (activeSessionId) {
    return (
      <SessionWorkspace
        sessionId={activeSessionId}
        onBack={() => setActiveSessionId(null)}
        toast={toast}
      />
    );
  }

  if (selectedClassId) {
    return (
      <ClassDetailView
        classId={selectedClassId}
        onBack={() => { setSelectedClassId(null); load(); }}
        onOpenSessionWorkspace={(sid) => setActiveSessionId(sid)}
        toast={toast}
      />
    );
  }

  return (
    <div className="stack-lg">
      <div className="row-between">
        <div>
          <h2>Các lớp học phần của tôi</h2>
          <p className="muted small">Giảng viên đa trường — chọn trường khi khởi tạo lớp và quản lý điểm danh theo từng lớp.</p>
        </div>
        <button type="button" className="btn btn-primary" onClick={() => setShowCreate(true)}>
          <Plus size={16} /> Tạo lớp học phần
        </button>
      </div>

      <div className="class-grid">
        {classes.map((c) => (
          <div key={c.id} className="class-card" onClick={() => setSelectedClassId(c.id)}>
            <div className="row-between">
              <span className="badge badge-green">{c.school_code}</span>
              <span className="muted tiny">{c.code}</span>
            </div>
            <h3>{c.name}</h3>
            <div className="muted small">{c.school_name}</div>
            <div className="muted tiny">{c.term || '—'} · {c.school_year || '—'}</div>
            <div className="class-stats">
              <span>Whitelist: <strong>{c.whitelist_count}</strong></span>
              <span>Ghi danh: <strong>{c.student_count}</strong></span>
              <span>Chờ chốt: <strong>{c.pending_session_count}</strong></span>
            </div>
          </div>
        ))}
        {classes.length === 0 && (
          <div className="card">Chưa có lớp học phần nào. Bấm "Tạo lớp học phần" để bắt đầu.</div>
        )}
      </div>

      {showCreate && (
        <div className="modal-backdrop">
          <div className="modal modal-md">
            <div className="modal-head">
              <h3>Tạo lớp học phần mới</h3>
              <button type="button" className="icon-btn" onClick={() => setShowCreate(false)}><X size={18} /></button>
            </div>
            <form onSubmit={handleCreateClass} className="stack">
              <label>
                Thuộc trường học
                <select value={form.schoolId} onChange={(e) => setForm({ ...form, schoolId: e.target.value })} required>
                  {schools.map((s) => <option key={s.id} value={s.id}>{s.name} ({s.code})</option>)}
                </select>
              </label>
              <div className="grid-2">
                <label>
                  Tên môn / lớp học phần
                  <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required />
                </label>
                <label>
                  Mã lớp
                  <input value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} placeholder="VD: INT3306-01" required />
                </label>
                <label>
                  Học kỳ
                  <input value={form.term} onChange={(e) => setForm({ ...form, term: e.target.value })} />
                </label>
                <label>
                  Niên khóa
                  <input value={form.schoolYear} onChange={(e) => setForm({ ...form, schoolYear: e.target.value })} />
                </label>
              </div>
              <label>
                File Excel Danh sách MSSV hợp lệ (Whitelist - tùy chọn)
                <input type="file" accept=".xlsx,.xls" onChange={(e) => setWhitelistFile(e.target.files?.[0] || null)} />
              </label>
              <div className="row-between">
                <button type="button" className="btn btn-outline" onClick={() => setShowCreate(false)}>Hủy</button>
                <button type="submit" className="btn btn-primary" disabled={busy}>Tạo lớp</button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
