import React, { useEffect, useState } from 'react';
import { apiFetch, FLAG_LABELS, formatDateTime, getDeviceFingerprint, getToken, RESULT_META, SESSION_STATUS_META, setToken } from '../api';
import QrScannerModal from './QrScannerModal';
import {
  AlertTriangle,
  ArrowLeft,
  CheckCircle2,
  Clock,
  Hash,
  History,
  LogIn,
  MapPin,
  QrCode,
  ShieldAlert,
  Smartphone,
  X,
  XCircle,
} from 'lucide-react';

export function MobileProbeScreen({ probeToken }) {
  const [info, setInfo] = useState(null);
  const [currentUser, setCurrentUser] = useState(null);
  const [needsLogin, setNeedsLogin] = useState(!getToken());
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [status, setStatus] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function loadProbe() {
    setError('');
    if (!getToken()) {
      setNeedsLogin(true);
      setInfo(null);
      return;
    }
    try {
      const me = await apiFetch('/auth/me');
      setCurrentUser(me);
      if (me.role !== 'LECTURER') {
        setNeedsLogin(true);
        setError('Tài khoản đang đăng nhập không phải Giảng viên. Vui lòng đăng nhập đúng tài khoản Giảng viên mở phiên.');
        return;
      }
      const data = await apiFetch(`/sessions/center-probe/${probeToken}`);
      setInfo(data);
      setNeedsLogin(false);
    } catch (e) {
      if (e.status === 401 || e.status === 403) {
        setNeedsLogin(true);
      }
      setError(e.message);
    }
  }

  useEffect(() => {
    loadProbe();
  }, [probeToken]);

  async function handleLogin(e) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      const data = await apiFetch('/auth/login', {
        method: 'POST',
        body: { email: email.trim(), password },
      });
      setToken(data.accessToken);
      await loadProbe();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  function sendLocation() {
    setError('');
    setStatus('');
    if (!navigator.geolocation) {
      setError('Trình duyệt điện thoại không hỗ trợ định vị GPS.');
      return;
    }
    setBusy(true);
    navigator.geolocation.getCurrentPosition(
      async (pos) => {
        try {
          const res = await apiFetch(`/sessions/center-probe/${probeToken}/location`, {
            method: 'POST',
            body: {
              latitude: pos.coords.latitude,
              longitude: pos.coords.longitude,
              accuracyMeters: pos.coords.accuracy,
            },
          });
          setStatus(`Thành công! Đã gửi tọa độ (${res.latitude.toFixed(6)}, ${res.longitude.toFixed(6)}, sai số ~${Math.round(res.accuracyMeters)}m) về màn hình mở phiên.`);
        } catch (err) {
          setError(err.message);
        } finally {
          setBusy(false);
        }
      },
      (err) => {
        setBusy(false);
        setError('Không lấy được GPS (' + err.message + '). Vui lòng bật quyền vị trí cho trình duyệt.');
      },
      { enableHighAccuracy: true, timeout: 12000, maximumAge: 0 },
    );
  }

  return (
    <div className="login-page">
      <div className="login-card" style={{ maxWidth: 460 }}>
        <div className="login-brand">
          <div className="brand-badge">GPS</div>
          <div>
            <h2>Chốt tâm điểm phiên điểm danh</h2>
            <p className="muted small">Xác thực Giảng viên & Ghi nhận GPS điện thoại</p>
          </div>
        </div>
        {error && <div className="alert alert-err">{error}</div>}
        {needsLogin ? (
          <form onSubmit={handleLogin} className="stack">
            <p className="muted small">
              Để đảm bảo chỉ đúng Giảng viên tạo phiên được chốt tâm điểm, vui lòng đăng nhập tài khoản Giảng viên trên điện thoại.
            </p>
            <label>
              Email Giảng viên
              <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
            </label>
            <label>
              Mật khẩu
              <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
            </label>
            <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
              <LogIn size={16} /> {busy ? 'Đang xác thực...' : 'Đăng nhập Giảng viên'}
            </button>
          </form>
        ) : (
          <>
            {currentUser && (
              <div className="muted small row-between">
                <span>Giảng viên: <strong>{currentUser.full_name}</strong></span>
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  onClick={() => { setToken(''); setCurrentUser(null); setInfo(null); setNeedsLogin(true); }}
                >
                  Đổi tài khoản
                </button>
              </div>
            )}
            {info && (
              <div className="alert alert-info">
                Lớp: <strong>{info.class_name} ({info.class_code})</strong><br />
                Trường: {info.school_name}
              </div>
            )}
            {status && <div className="alert alert-ok">{status}</div>}
            <button type="button" className="btn btn-primary btn-block" onClick={sendLocation} disabled={busy || !info}>
              <MapPin size={16} /> {busy ? 'Đang lấy tọa độ GPS...' : 'Gửi tọa độ GPS phòng học ngay'}
            </button>
          </>
        )}
      </div>
    </div>
  );
}

function CheckInModal({ user, onCheckedIn, onClose, toast }) {
  const [mode, setMode] = useState('QR');
  const [qrToken, setQrToken] = useState('');
  const [pin, setPin] = useState('');
  const [scannerOpen, setScannerOpen] = useState(false);
  const [gpsState, setGpsState] = useState({ loading: true, lat: null, lng: null, accuracy: null, denied: false, note: 'Đang lấy tọa độ GPS...' });
  const [confirmDeviceBinding, setConfirmDeviceBinding] = useState(true);
  const [needsExplicitConfirm, setNeedsExplicitConfirm] = useState(!user.hasBaselineDevice);
  const [outcome, setOutcome] = useState(null);
  const [busy, setBusy] = useState(false);

  function fetchGps() {
    if (!navigator.geolocation) {
      setGpsState({ loading: false, lat: null, lng: null, accuracy: null, denied: false, note: 'Trình duyệt không hỗ trợ GPS (sẽ ghi nhận thiếu GPS -> Chờ duyệt)' });
      return;
    }
    setGpsState((s) => ({ ...s, loading: true, note: 'Đang lấy tọa độ GPS...' }));
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setGpsState({
          loading: false,
          lat: pos.coords.latitude,
          lng: pos.coords.longitude,
          accuracy: pos.coords.accuracy,
          denied: false,
          note: `Tọa độ: ${pos.coords.latitude.toFixed(6)}, ${pos.coords.longitude.toFixed(6)} (sai số ±${Math.round(pos.coords.accuracy)}m)`,
        });
      },
      (err) => {
        const denied = err.code === 1;
        setGpsState({
          loading: false,
          lat: null,
          lng: null,
          accuracy: null,
          denied,
          note: denied
            ? 'Bạn đã từ chối quyền vị trí (sẽ gắn cờ Từ chối quyền vị trí -> Chờ duyệt)'
            : 'Không lấy được tín hiệu GPS (sẽ gắn cờ Không có GPS -> Chờ duyệt)',
        });
      },
      { enableHighAccuracy: true, timeout: 8000, maximumAge: 0 },
    );
  }

  useEffect(() => {
    fetchGps();
  }, []);

  async function submitCheckIn(e) {
    e?.preventDefault();
    if (mode === 'QR' && !qrToken.trim()) {
      toast('Vui lòng quét hoặc nhập chuỗi mã QR điểm danh', 'err');
      return;
    }
    if (mode === 'PIN' && pin.trim().length !== 6) {
      toast('Vui lòng nhập đủ 6 chữ số mã PIN', 'err');
      return;
    }
    setBusy(true);
    setOutcome(null);
    try {
      const res = await apiFetch('/checkins', {
        method: 'POST',
        body: {
          qrToken: mode === 'QR' ? qrToken.trim() : null,
          pin: mode === 'PIN' ? pin.trim() : null,
          latitude: gpsState.lat,
          longitude: gpsState.lng,
          accuracyMeters: gpsState.accuracy,
          locationDenied: gpsState.denied,
          deviceFingerprint: getDeviceFingerprint(),
          confirmDeviceBinding: !needsExplicitConfirm || confirmDeviceBinding,
        },
      });
      setOutcome({ ok: true, ...res });
      onCheckedIn?.();
    } catch (err) {
      if (err.code === 'DEVICE_CONFIRM_REQUIRED') {
        setNeedsExplicitConfirm(true);
        setConfirmDeviceBinding(false);
        toast(err.message, 'err');
      } else {
        setOutcome({ ok: false, statusLabel: 'Không thành công', message: err.message });
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop">
      <div className="modal modal-md">
        <div className="modal-head">
          <h3><QrCode size={18} /> Điểm danh buổi học</h3>
          <button type="button" className="icon-btn" onClick={onClose}><X size={18} /></button>
        </div>

        <div className="pill-switch" style={{ marginBottom: 12 }}>
          <button type="button" className={mode === 'QR' ? 'active' : ''} onClick={() => setMode('QR')}>
            <QrCode size={14} /> Quét mã QR
          </button>
          <button type="button" className={mode === 'PIN' ? 'active' : ''} onClick={() => setMode('PIN')}>
            <Hash size={14} /> Nhập mã PIN 6 số
          </button>
        </div>

        <div className={`alert ${gpsState.denied ? 'alert-warn' : gpsState.lat != null ? 'alert-ok' : 'alert-info'}`}>
          <MapPin size={15} /> {gpsState.note}
          <button type="button" className="btn btn-ghost btn-sm" onClick={fetchGps} style={{ marginLeft: 'auto' }}>Cập nhật GPS</button>
        </div>

        {needsExplicitConfirm && (
          <label className="checkbox-card">
            <input
              type="checkbox"
              checked={confirmDeviceBinding}
              onChange={(e) => setConfirmDeviceBinding(e.target.checked)}
            />
            <span>
              <strong>Gắn thiết bị gốc cố định:</strong> Tài khoản chưa có thiết bị gốc. Xác nhận gắn trình duyệt/thiết bị hiện tại làm thiết bị gốc để điểm danh.
            </span>
          </label>
        )}

        <form onSubmit={submitCheckIn} className="stack" style={{ marginTop: 12 }}>
          {mode === 'QR' ? (
            <div className="stack">
              <button type="button" className="btn btn-outline" onClick={() => setScannerOpen(true)}>
                <QrCode size={16} /> Mở Camera quét QR máy chiếu
              </button>
              <label>
                Chuỗi mã QR (tự điền sau khi quét)
                <input value={qrToken} onChange={(e) => setQrToken(e.target.value)} placeholder="ATT_..." />
              </label>
            </div>
          ) : (
            <label>
              Mã PIN 6 chữ số trên máy chiếu
              <input
                value={pin}
                onChange={(e) => setPin(e.target.value.replace(/\D/g, '').slice(0, 6))}
                placeholder="6 chữ số"
                maxLength={6}
                style={{ fontSize: 22, letterSpacing: 6, textAlign: 'center', fontWeight: 700 }}
              />
            </label>
          )}

          <button type="submit" className="btn btn-primary btn-block" disabled={busy || gpsState.loading}>
            {busy ? 'Đang đối chiếu 6 bước...' : gpsState.loading ? 'Đang lấy vị trí...' : 'Gửi điểm danh'}
          </button>
        </form>

        {outcome && (
          <div className={`status-feedback ${
            !outcome.ok
              ? 'feedback-fail'
              : outcome.result === 'PRESENT'
                ? 'feedback-ok'
                : outcome.result === 'REVIEW'
                  ? 'feedback-review'
                  : 'feedback-pending'
          }`}>
            <div className="feedback-title">
              {!outcome.ok && <XCircle size={20} />}
              {outcome.ok && outcome.result === 'PRESENT' && <CheckCircle2 size={20} />}
              {outcome.ok && outcome.result === 'REVIEW' && <Clock size={20} />}
              {outcome.ok && outcome.result === 'PENDING' && <AlertTriangle size={20} />}
              <strong>{outcome.statusLabel}</strong>
            </div>
            <div>{outcome.message}</div>
            {outcome.flags?.length > 0 && (
              <div className="flag-list" style={{ marginTop: 6 }}>
                {outcome.flags.map((f) => <span key={f} className="badge badge-warn tiny">{FLAG_LABELS[f] || f}</span>)}
              </div>
            )}
          </div>
        )}

        {scannerOpen && (
          <QrScannerModal
            title="Quét QR điểm danh phiên"
            onClose={() => setScannerOpen(false)}
            onDetected={(raw) => {
              setScannerOpen(false);
              setQrToken(raw);
              toast('Đã đọc được mã QR điểm danh');
            }}
          />
        )}
      </div>
    </div>
  );
}

export default function StudentView({ user, initialJoinToken, onClearJoinToken, onRefreshUser, toast }) {
  const [tab, setTab] = useState('classes');
  const [classes, setClasses] = useState([]);
  const [history, setHistory] = useState([]);
  const [selectedClass, setSelectedClass] = useState(null);
  const [checkInOpen, setCheckInOpen] = useState(false);
  const [joinOpen, setJoinOpen] = useState(Boolean(initialJoinToken));
  const [joinToken, setJoinToken] = useState(initialJoinToken || '');
  const [joinScanner, setJoinScanner] = useState(false);

  async function loadAll() {
    const [cls, hist] = await Promise.all([apiFetch('/classes'), apiFetch('/student/history')]);
    setClasses(cls);
    setHistory(hist);
  }

  useEffect(() => {
    loadAll().catch((e) => toast(e.message, 'err'));
    if (initialJoinToken) {
      let tok = initialJoinToken.trim();
      if (tok.includes('?join=')) tok = tok.split('?join=')[1].split('&')[0].trim();
      if (tok) {
        apiFetch('/classes/join-by-qr', { method: 'POST', body: { staticToken: tok } })
          .then(async (res) => {
            toast(`Đã mở & liên kết lớp ${res.className} (${res.classCode})`);
            setJoinOpen(false);
            setJoinToken('');
            onClearJoinToken?.();
            await loadAll();
            await openClassDetail(res.classId);
          })
          .catch((e) => toast(e.message, 'err'));
      }
    }
  }, []);

  async function openClassDetail(id) {
    try {
      const res = await apiFetch(`/classes/${id}`);
      setSelectedClass(res);
    } catch (err) {
      toast(err.message, 'err');
    }
  }

  async function submitJoinByQr(e) {
    e?.preventDefault();
    let tok = joinToken.trim();
    if (tok.includes('?join=')) {
      tok = tok.split('?join=')[1].split('&')[0].trim();
    }
    if (!tok) return;
    try {
      const res = await apiFetch('/classes/join-by-qr', { method: 'POST', body: { staticToken: tok } });
      toast(`Đã tham gia lớp ${res.className} (${res.classCode})`);
      setJoinOpen(false);
      setJoinToken('');
      onClearJoinToken?.();
      await loadAll();
      await openClassDetail(res.classId);
    } catch (err) {
      toast(err.message, 'err');
    }
  }

  if (selectedClass) {
    const ci = selectedClass.classInfo;
    const records = selectedClass.records || [];
    return (
      <div className="stack-lg">
        <div className="card-head">
          <div className="row-actions">
            <button type="button" className="btn btn-outline btn-sm" onClick={() => setSelectedClass(null)}><ArrowLeft size={15} /> Danh sách lớp</button>
            <div>
              <h2>{ci.name} ({ci.code})</h2>
              <div className="muted small">{ci.schoolName} · GV: {ci.lecturerName} · {ci.term} {ci.schoolYear}</div>
            </div>
          </div>
          <button type="button" className="btn btn-primary" onClick={() => setCheckInOpen(true)}>
            <QrCode size={15} /> Điểm danh ngay
          </button>
        </div>

        <div className="card">
          <h3>Chi tiết chuyên cần từng buổi học</h3>
          <div className="table-wrap" style={{ marginTop: 10 }}>
            <table>
              <thead>
                <tr><th>Thời gian buổi</th><th>Trạng thái phiên</th><th>Kết quả của bạn</th><th>Cờ & Ghi chú</th></tr>
              </thead>
              <tbody>
                {records.map((r) => {
                  const sm = SESSION_STATUS_META[r.session_status] || { label: r.session_status, cls: 'badge-slate' };
                  const rm = r.result ? (RESULT_META[r.result] || { label: r.result, cls: 'badge-slate' }) : null;
                  return (
                    <tr key={r.session_id}>
                      <td>{formatDateTime(r.started_at)}</td>
                      <td><span className={`badge ${sm.cls}`}>{sm.label}</span></td>
                      <td>{rm ? <span className={`badge ${rm.cls}`}>{rm.label}</span> : <span className="muted">Chưa có bản ghi</span>}</td>
                      <td>
                        <div className="flag-list">
                          {(r.flags || []).map((f) => <span key={f} className="badge badge-warn tiny">{FLAG_LABELS[f] || f}</span>)}
                        </div>
                        <div className="muted tiny">{r.message || '—'}</div>
                      </td>
                    </tr>
                  );
                })}
                {records.length === 0 && <tr><td colSpan={4} className="muted">Lớp chưa có buổi điểm danh nào.</td></tr>}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="stack-lg">
      <div className="card row-between">
        <div>
          <h2>Không gian Sinh viên · MSSV {user.student_no}</h2>
          <div className="muted small">
            {user.school_name} ({user.school_code}) · Thiết bị gốc:{' '}
            {user.hasBaselineDevice
              ? <span className="badge badge-green tiny"><Smartphone size={12} /> Đã gắn thiết bị gốc</span>
              : <span className="badge badge-amber tiny"><ShieldAlert size={12} /> Chưa gắn (gắn ngay ở lần điểm danh đầu tiên)</span>}
          </div>
        </div>
        <div className="row-actions">
          <button type="button" className="btn btn-outline" onClick={() => setJoinOpen(true)}>
            <QrCode size={15} /> Tham gia lớp qua QR tĩnh
          </button>
          <button type="button" className="btn btn-primary" onClick={() => setCheckInOpen(true)}>
            <QrCode size={15} /> Điểm danh (QR / PIN)
          </button>
        </div>
      </div>

      <div className="tab-bar">
        <button type="button" className={tab === 'classes' ? 'tab active' : 'tab'} onClick={() => setTab('classes')}>
          Lớp học phần đã liên kết ({classes.length})
        </button>
        <button type="button" className={tab === 'history' ? 'tab active' : 'tab'} onClick={() => setTab('history')}>
          <History size={14} /> Lịch sử điểm danh ({history.length})
        </button>
      </div>

      {tab === 'classes' && (
        <div className="class-grid">
          {classes.map((c) => (
            <div key={c.id} className="class-card" onClick={() => openClassDetail(c.id)}>
              <div className="row-between">
                <span className="badge badge-green">{c.code}</span>
                <span className="muted tiny">{c.term}</span>
              </div>
              <h3>{c.name}</h3>
              <div className="muted small">Giảng viên: {c.lecturer_name}</div>
              <div className="class-stats">
                <span>Có mặt: <strong>{c.present_count}</strong> / {c.finalized_session_count} buổi chốt</span>
              </div>
            </div>
          ))}
          {classes.length === 0 && (
            <div className="card">Chưa liên kết lớp nào. Bạn có thể quét QR tĩnh của lớp hoặc điểm danh trực tiếp trong giờ học để tự động liên kết.</div>
          )}
        </div>
      )}

      {tab === 'history' && (
        <div className="card">
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>Lớp học phần</th><th>Thời gian buổi</th><th>Trạng thái</th><th>Cờ & Diễn giải</th></tr>
              </thead>
              <tbody>
                {history.map((h) => {
                  const rm = RESULT_META[h.result] || { label: h.result, cls: 'badge-slate' };
                  return (
                    <tr key={h.session_id}>
                      <td><strong>{h.class_name}</strong> <span className="muted tiny">({h.class_code})</span></td>
                      <td>{formatDateTime(h.started_at)}</td>
                      <td><span className={`badge ${rm.cls}`}>{rm.label}</span></td>
                      <td>
                        <div className="flag-list">
                          {(h.flags || []).map((f) => <span key={f} className="badge badge-warn tiny">{FLAG_LABELS[f] || f}</span>)}
                        </div>
                        <div className="muted tiny">{h.message}</div>
                      </td>
                    </tr>
                  );
                })}
                {history.length === 0 && <tr><td colSpan={4} className="muted">Chưa có lịch sử điểm danh.</td></tr>}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {checkInOpen && (
        <CheckInModal
          user={user}
          onClose={() => setCheckInOpen(false)}
          onCheckedIn={() => { loadAll(); onRefreshUser?.(); }}
          toast={toast}
        />
      )}

      {joinOpen && (
        <div className="modal-backdrop">
          <div className="modal modal-sm">
            <div className="modal-head">
              <h3>Tham gia lớp bằng QR tĩnh</h3>
              <button type="button" className="icon-btn" onClick={() => { setJoinOpen(false); onClearJoinToken?.(); }}><X size={18} /></button>
            </div>
            <form onSubmit={submitJoinByQr} className="stack">
              <button type="button" className="btn btn-outline" onClick={() => setJoinScanner(true)}>
                <QrCode size={15} /> Mở Camera quét QR tĩnh của lớp
              </button>
              <label>
                Mã QR tĩnh hoặc đường dẫn lớp
                <input value={joinToken} onChange={(e) => setJoinToken(e.target.value)} placeholder="Dán mã hoặc URL ?join=..." required />
              </label>
              <button type="submit" className="btn btn-primary btn-block">Liên kết lớp học phần</button>
            </form>
          </div>
        </div>
      )}

      {joinScanner && (
        <QrScannerModal
          title="Quét QR tĩnh của lớp"
          onClose={() => setJoinScanner(false)}
          onDetected={(raw) => {
            setJoinScanner(false);
            setJoinToken(raw);
          }}
        />
      )}
    </div>
  );
}
