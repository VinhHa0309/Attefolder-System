import React, { useEffect, useState } from 'react';
import { apiFetch, formatDateTime, setToken } from '../api';
import { KeyRound, Mail, RefreshCw, ShieldCheck, UserPlus, LogIn } from 'lucide-react';

export function DevMailDrawer({ onPickActivation }) {
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState([]);
  const [err, setErr] = useState('');

  async function load() {
    setErr('');
    try {
      const data = await apiFetch('/auth/dev-outbox');
      setItems(Array.isArray(data) ? data : []);
    } catch (e) {
      setErr(e.message);
    }
  }

  useEffect(() => {
    if (open) load();
  }, [open]);

  return (
    <>
      <button type="button" className="btn btn-ghost btn-sm" onClick={() => setOpen(true)}>
        <Mail size={15} /> Hộp thư Dev (OTP / Kích hoạt)
      </button>
      {open && (
        <div className="modal-backdrop">
          <div className="modal modal-lg">
            <div className="modal-head">
              <h3><Mail size={18} /> Hộp thư hệ thống cục bộ (Dev Outbox)</h3>
              <div className="row-actions">
                <button type="button" className="btn btn-outline btn-sm" onClick={load}>
                  <RefreshCw size={14} /> Làm mới
                </button>
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => setOpen(false)}>Đóng</button>
              </div>
            </div>
            <p className="muted small">
              Khi chạy local chưa cấu hình máy chủ SMTP thật, mọi email kích hoạt Admin trường và mã OTP sẽ được lưu tại đây để kiểm thử.
            </p>
            {err && <div className="alert alert-err">{err}</div>}
            <div className="outbox-list">
              {items.length === 0 && <div className="empty-state">Chưa có email nào được gửi.</div>}
              {items.map((m) => {
                const tokenMatch = String(m.body || '').match(/\?activate=([A-Za-z0-9_-]+)/);
                return (
                  <div key={m.id} className="outbox-item">
                    <div className="outbox-meta">
                      <strong>{m.subject}</strong>
                      <span className="muted small">{m.recipient_email} · {formatDateTime(m.created_at)}</span>
                    </div>
                    <pre className="outbox-body">{m.body}</pre>
                    {tokenMatch && onPickActivation && (
                      <button
                        type="button"
                        className="btn btn-primary btn-sm"
                        onClick={() => {
                          setOpen(false);
                          onPickActivation(tokenMatch[1]);
                        }}
                      >
                        Mở liên kết kích hoạt ngay
                      </button>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      )}
    </>
  );
}

export function ChangePasswordModal({ user, forced = false, onSuccess, onClose, onLogout }) {
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    setError('');
    if (newPassword.length < 12) {
      setError('Mật khẩu mới phải có ít nhất 12 ký tự.');
      return;
    }
    if (newPassword !== confirmPassword) {
      setError('Xác nhận mật khẩu mới không khớp.');
      return;
    }
    setBusy(true);
    try {
      await apiFetch('/auth/change-password', {
        method: 'POST',
        body: { currentPassword, newPassword },
      });
      onSuccess?.();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop">
      <div className="modal modal-sm">
        <div className="modal-head">
          <h3><KeyRound size={18} /> {forced ? 'Đổi mật khẩu bắt buộc lần đầu' : 'Đổi mật khẩu tài khoản'}</h3>
          {!forced && <button type="button" className="btn btn-ghost btn-sm" onClick={onClose}>Đóng</button>}
        </div>
        <p className="muted small">
          {forced
            ? `Tài khoản ${user?.full_name} đang dùng mật khẩu tạm do trường cấp. Vui lòng đặt mật khẩu mới (tối thiểu 12 ký tự) để tiếp tục.`
            : 'Nhập mật khẩu hiện tại và mật khẩu mới (12 - 128 ký tự).'}
        </p>
        {error && <div className="alert alert-err">{error}</div>}
        <form onSubmit={submit} className="stack">
          <label>
            Mật khẩu hiện tại
            <input type="password" value={currentPassword} onChange={(e) => setCurrentPassword(e.target.value)} required />
          </label>
          <label>
            Mật khẩu mới (tối thiểu 12 ký tự)
            <input type="password" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} minLength={12} required />
          </label>
          <label>
            Nhập lại mật khẩu mới
            <input type="password" value={confirmPassword} onChange={(e) => setConfirmPassword(e.target.value)} minLength={12} required />
          </label>
          <div className="row-between">
            {forced ? (
              <button type="button" className="btn btn-outline" onClick={onLogout}>Đăng xuất</button>
            ) : <span />}
            <button type="submit" className="btn btn-primary" disabled={busy}>
              {busy ? 'Đang lưu...' : 'Cập nhật mật khẩu'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

export default function AuthScreens({ initialActivationToken, onAuthenticated, toast }) {
  const [mode, setMode] = useState(initialActivationToken ? 'activate' : 'login');
  const [loginTab, setLoginTab] = useState('STUDENT');
  const [schools, setSchools] = useState([]);

  // Login form
  const [schoolId, setSchoolId] = useState('');
  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');

  // Lecturer Register + OTP
  const [regName, setRegName] = useState('');
  const [regGender, setRegGender] = useState('MALE');
  const [regDob, setRegDob] = useState('');
  const [regEmail, setRegEmail] = useState('');
  const [regPass, setRegPass] = useState('');
  const [otpStep, setOtpStep] = useState(false);
  const [otpCode, setOtpCode] = useState('');
  const [resendCooldown, setResendCooldown] = useState(0);

  // Forgot/Reset Password
  const [fpRole, setFpRole] = useState('STUDENT');
  const [fpSchoolId, setFpSchoolId] = useState('');
  const [fpStudentNo, setFpStudentNo] = useState('');
  const [fpEmail, setFpEmail] = useState('');
  const [fpStep, setFpStep] = useState(1);
  const [fpOtp, setFpOtp] = useState('');
  const [fpNewPass, setFpNewPass] = useState('');
  const [fpHint, setFpHint] = useState('');

  // Activation token
  const [actToken, setActToken] = useState(initialActivationToken || '');
  const [actInfo, setActInfo] = useState(null);
  const [actPass, setActPass] = useState('');
  const [actPass2, setActPass2] = useState('');

  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    apiFetch('/public/schools')
      .then((list) => {
        setSchools(list);
        if (list.length > 0) {
          setSchoolId(list[0].id);
          setFpSchoolId(list[0].id);
        }
      })
      .catch(() => {});
  }, []);

  useEffect(() => {
    if (resendCooldown <= 0) return;
    const t = setTimeout(() => setResendCooldown((v) => v - 1), 1000);
    return () => clearTimeout(t);
  }, [resendCooldown]);

  useEffect(() => {
    if (mode !== 'activate' || !actToken) return;
    setError('');
    apiFetch(`/auth/activation-info?token=${encodeURIComponent(actToken)}`)
      .then((info) => setActInfo(info))
      .catch((err) => {
        setActInfo(null);
        setError(err.message);
      });
  }, [mode, actToken]);

  async function handleLogin(e) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      const payload = loginTab === 'STUDENT'
        ? { email: identifier.trim(), password, schoolId }
        : { email: identifier.trim(), password };
      const data = await apiFetch('/auth/login', { method: 'POST', body: payload });
      setToken(data.accessToken);
      onAuthenticated(data);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  async function handleLecturerRegister(e) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      const res = await apiFetch('/auth/lecturer/register', {
        method: 'POST',
        body: { fullName: regName, gender: regGender, dateOfBirth: regDob, email: regEmail, password: regPass },
      });
      setOtpStep(true);
      setResendCooldown(30);
      toast?.(res.devOtp ? `${res.message} (OTP dev: ${res.devOtp})` : res.message);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  async function handleVerifyOtp(e) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      const res = await apiFetch('/auth/lecturer/verify-otp', {
        method: 'POST',
        body: { email: regEmail, otp: otpCode.trim() },
      });
      toast?.(res.message);
      setMode('login');
      setLoginTab('STAFF');
      setIdentifier(regEmail);
      setOtpStep(false);
      setOtpCode('');
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  async function handleResendOtp() {
    setError('');
    try {
      const res = await apiFetch('/auth/lecturer/resend-otp', { method: 'POST', body: { email: regEmail } });
      setResendCooldown(30);
      toast?.(res.devOtp ? `${res.message} (OTP dev: ${res.devOtp})` : res.message);
    } catch (err) {
      setError(err.message);
    }
  }

  async function handleForgot(e) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      const res = await apiFetch('/auth/forgot-password', {
        method: 'POST',
        body: {
          role: fpRole,
          schoolId: fpRole === 'STUDENT' ? fpSchoolId : null,
          studentNo: fpRole === 'STUDENT' ? fpStudentNo.trim() : null,
          email: fpRole !== 'STUDENT' ? fpEmail.trim() : null,
        },
      });
      setFpStep(2);
      setFpHint(res.devOtp ? `${res.message} · OTP dev: ${res.devOtp}` : res.message);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  async function handleResetPassword(e) {
    e.preventDefault();
    setError('');
    if (fpNewPass.length < 12) {
      setError('Mật khẩu mới tối thiểu 12 ký tự.');
      return;
    }
    setBusy(true);
    try {
      const res = await apiFetch('/auth/reset-password', {
        method: 'POST',
        body: {
          role: fpRole,
          schoolId: fpRole === 'STUDENT' ? fpSchoolId : null,
          studentNo: fpRole === 'STUDENT' ? fpStudentNo.trim() : null,
          email: fpRole !== 'STUDENT' ? fpEmail.trim() : null,
          otp: fpOtp.trim(),
          newPassword: fpNewPass,
        },
      });
      toast?.(res.message);
      setMode('login');
      setFpStep(1);
      setFpOtp('');
      setFpNewPass('');
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  async function handleActivate(e) {
    e.preventDefault();
    setError('');
    if (actPass.length < 12) {
      setError('Mật khẩu kích hoạt tối thiểu 12 ký tự.');
      return;
    }
    if (actPass !== actPass2) {
      setError('Mật khẩu xác nhận không khớp.');
      return;
    }
    setBusy(true);
    try {
      const res = await apiFetch('/auth/activate', {
        method: 'POST',
        body: { token: actToken.trim(), newPassword: actPass },
      });
      toast?.(res.message);
      window.history.replaceState({}, '', '/');
      setMode('login');
      setLoginTab('STAFF');
      if (actInfo?.email) setIdentifier(actInfo.email);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="login-brand">
          <div className="brand-badge">ATT</div>
          <div>
            <h1>Hệ thống Điểm danh Sinh viên</h1>
            <p className="muted small">Đa trường · QR động & PIN 15s · GPS & Đối soát cụm MAD</p>
          </div>
        </div>

        <div className="tab-bar">
          <button type="button" className={mode === 'login' ? 'tab active' : 'tab'} onClick={() => { setMode('login'); setError(''); }}>
            <LogIn size={15} /> Đăng nhập
          </button>
          <button type="button" className={mode === 'register' ? 'tab active' : 'tab'} onClick={() => { setMode('register'); setError(''); }}>
            <UserPlus size={15} /> Đăng ký Giảng viên
          </button>
          <button type="button" className={mode === 'forgot' ? 'tab active' : 'tab'} onClick={() => { setMode('forgot'); setError(''); }}>
            <KeyRound size={15} /> Quên mật khẩu
          </button>
          <button type="button" className={mode === 'activate' ? 'tab active' : 'tab'} onClick={() => { setMode('activate'); setError(''); }}>
            <ShieldCheck size={15} /> Kích hoạt Admin
          </button>
        </div>

        {error && <div className="alert alert-err">{error}</div>}

        {mode === 'login' && (
          <>
            <div className="pill-switch">
              <button
                type="button"
                className={loginTab === 'STUDENT' ? 'active' : ''}
                onClick={() => { setLoginTab('STUDENT'); setIdentifier(''); setError(''); }}
              >
                Sinh viên (Trường + MSSV)
              </button>
              <button
                type="button"
                className={loginTab === 'STAFF' ? 'active' : ''}
                onClick={() => { setLoginTab('STAFF'); setIdentifier(''); setError(''); }}
              >
                Giảng viên / Quản trị
              </button>
            </div>
            <form onSubmit={handleLogin} className="stack">
              {loginTab === 'STUDENT' ? (
                <>
                  <label>
                    Trường học
                    <select value={schoolId} onChange={(e) => setSchoolId(e.target.value)} required>
                      <option value="">-- Chọn trường --</option>
                      {schools.map((s) => (
                        <option key={s.id} value={s.id}>{s.name} ({s.code})</option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Mã số sinh viên (MSSV)
                    <input value={identifier} onChange={(e) => setIdentifier(e.target.value)} placeholder="VD: 00112345" required />
                  </label>
                </>
              ) : (
                <label>
                  Email đăng nhập
                  <input type="email" value={identifier} onChange={(e) => setIdentifier(e.target.value)} placeholder="lecturer1@campus.test" required />
                </label>
              )}
              <label>
                Mật khẩu
                <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
              </label>
              <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
                {busy ? 'Đang đăng nhập...' : 'Đăng nhập'}
              </button>
            </form>
          </>
        )}

        {mode === 'register' && !otpStep && (
          <form onSubmit={handleLecturerRegister} className="stack">
            <label>
              Họ và tên giảng viên
              <input value={regName} onChange={(e) => setRegName(e.target.value)} required />
            </label>
            <div className="grid-2">
              <label>
                Giới tính
                <select value={regGender} onChange={(e) => setRegGender(e.target.value)}>
                  <option value="MALE">Nam</option>
                  <option value="FEMALE">Nữ</option>
                  <option value="OTHER">Khác</option>
                </select>
              </label>
              <label>
                Ngày sinh
                <input type="date" value={regDob} onChange={(e) => setRegDob(e.target.value)} required />
              </label>
            </div>
            <label>
              Email đăng ký (nhận mã OTP)
              <input type="email" value={regEmail} onChange={(e) => setRegEmail(e.target.value)} required />
            </label>
            <label>
              Mật khẩu (tối thiểu 12 ký tự)
              <input type="password" value={regPass} onChange={(e) => setRegPass(e.target.value)} minLength={12} required />
            </label>
            <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
              {busy ? 'Đang gửi OTP...' : 'Đăng ký & Gửi mã OTP'}
            </button>
          </form>
        )}

        {mode === 'register' && otpStep && (
          <form onSubmit={handleVerifyOtp} className="stack">
            <div className="alert alert-info">
              Mã OTP 6 chữ số đã gửi tới <strong>{regEmail}</strong>. Nếu dùng môi trường test cục bộ, xem mã tại <em>Hộp thư Dev</em> bên dưới.
            </div>
            <label>
              Mã OTP 6 chữ số
              <input value={otpCode} onChange={(e) => setOtpCode(e.target.value)} maxLength={6} required />
            </label>
            <button type="submit" className="btn btn-primary btn-block" disabled={busy}>Xác nhận OTP & Kích hoạt</button>
            <div className="row-between">
              <button type="button" className="btn btn-outline btn-sm" onClick={handleResendOtp} disabled={resendCooldown > 0}>
                {resendCooldown > 0 ? `Gửi lại OTP (${resendCooldown}s)` : 'Gửi lại OTP'}
              </button>
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => setOtpStep(false)}>Quay lại</button>
            </div>
          </form>
        )}

        {mode === 'forgot' && fpStep === 1 && (
          <form onSubmit={handleForgot} className="stack">
            <p className="muted small">Áp dụng cho Sinh viên, Giảng viên và Admin trường (Quản trị hệ thống nằm ngoài phạm vi khôi phục).</p>
            <label>
              Loại tài khoản
              <select value={fpRole} onChange={(e) => setFpRole(e.target.value)}>
                <option value="STUDENT">Sinh viên</option>
                <option value="LECTURER">Giảng viên</option>
                <option value="SCHOOL_ADMIN">Quản trị viên trường</option>
              </select>
            </label>
            {fpRole === 'STUDENT' ? (
              <>
                <label>
                  Trường học
                  <select value={fpSchoolId} onChange={(e) => setFpSchoolId(e.target.value)} required>
                    {schools.map((s) => <option key={s.id} value={s.id}>{s.name} ({s.code})</option>)}
                  </select>
                </label>
                <label>
                  Mã số sinh viên
                  <input value={fpStudentNo} onChange={(e) => setFpStudentNo(e.target.value)} required />
                </label>
              </>
            ) : (
              <label>
                {fpRole === 'SCHOOL_ADMIN' ? 'Email đăng nhập / Email khôi phục của trường' : 'Email tài khoản giảng viên'}
                <input type="email" value={fpEmail} onChange={(e) => setFpEmail(e.target.value)} required />
              </label>
            )}
            <button type="submit" className="btn btn-primary btn-block" disabled={busy}>Gửi mã OTP khôi phục</button>
          </form>
        )}

        {mode === 'forgot' && fpStep === 2 && (
          <form onSubmit={handleResetPassword} className="stack">
            <div className="alert alert-info">{fpHint}</div>
            <label>
              Mã OTP khôi phục
              <input value={fpOtp} onChange={(e) => setFpOtp(e.target.value)} maxLength={6} required />
            </label>
            <label>
              Mật khẩu mới (tối thiểu 12 ký tự)
              <input type="password" value={fpNewPass} onChange={(e) => setFpNewPass(e.target.value)} minLength={12} required />
            </label>
            <div className="row-between">
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => setFpStep(1)}>Quay lại</button>
              <button type="submit" className="btn btn-primary" disabled={busy}>Đặt lại mật khẩu</button>
            </div>
          </form>
        )}

        {mode === 'activate' && (
          <form onSubmit={handleActivate} className="stack">
            <label>
              Mã kích hoạt từ email
              <input value={actToken} onChange={(e) => setActToken(e.target.value)} placeholder="Dán token sau ?activate=..." required />
            </label>
            {actInfo && (
              <div className="alert alert-info">
                Kích hoạt Admin cho <strong>{actInfo.schoolName} ({actInfo.schoolCode})</strong> · Email: <strong>{actInfo.email}</strong>
              </div>
            )}
            <label>
              Mật khẩu mới (tối thiểu 12 ký tự)
              <input type="password" value={actPass} onChange={(e) => setActPass(e.target.value)} minLength={12} required />
            </label>
            <label>
              Xác nhận mật khẩu
              <input type="password" value={actPass2} onChange={(e) => setActPass2(e.target.value)} minLength={12} required />
            </label>
            <button type="submit" className="btn btn-primary btn-block" disabled={busy || !actToken}>
              Hoàn tất kích hoạt Admin
            </button>
          </form>
        )}

        <div className="login-footer">
          <DevMailDrawer onPickActivation={(t) => { setActToken(t); setMode('activate'); }} />
        </div>
      </div>
    </div>
  );
}
