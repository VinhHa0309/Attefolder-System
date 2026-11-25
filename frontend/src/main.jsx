import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';
import { apiFetch, getToken, setToken } from './api';
import AuthScreens, { ChangePasswordModal } from './components/AuthScreens';
import { SchoolAdminView, SystemAdminView } from './components/AdminViews';
import LecturerView from './components/LecturerViews';
import StudentView, { MobileProbeScreen } from './components/StudentViews';
import { KeyRound, LogOut, UserCheck } from 'lucide-react';

const ROLE_LABELS = {
  SYSTEM_ADMIN: 'Quản trị hệ thống',
  SCHOOL_ADMIN: 'Quản trị viên trường',
  LECTURER: 'Giảng viên',
  STUDENT: 'Sinh viên',
};

function App() {
  const params = new URLSearchParams(window.location.search);
  const probeToken = params.get('probe');
  const initialActivationToken = params.get('activate');
  const [joinToken, setJoinToken] = useState(params.get('join') || '');

  const [user, setUser] = useState(null);
  const [booting, setBooting] = useState(!probeToken && Boolean(getToken()));
  const [changePassOpen, setChangePassOpen] = useState(false);
  const [toastMsg, setToastMsg] = useState(null);

  function toast(msg, type = 'ok') {
    setToastMsg({ msg, type, id: Date.now() });
  }

  useEffect(() => {
    if (!toastMsg) return;
    const t = setTimeout(() => setToastMsg(null), 4500);
    return () => clearTimeout(t);
  }, [toastMsg]);

  async function refreshMe() {
    const me = await apiFetch('/auth/me');
    setUser(me);
  }

  useEffect(() => {
    if (probeToken || !getToken()) return;
    refreshMe()
      .catch(() => {
        setToken('');
        setUser(null);
      })
      .finally(() => setBooting(false));
  }, [probeToken]);

  async function handleLogout() {
    try {
      await apiFetch('/auth/logout', { method: 'POST' });
    } catch {
      // ignore
    }
    setToken('');
    setUser(null);
    setChangePassOpen(false);
    toast('Đã đăng xuất');
  }

  if (probeToken) {
    return <MobileProbeScreen probeToken={probeToken} />;
  }

  if (booting) {
    return <div className="login-page"><div className="card">Đang khôi phục phiên đăng nhập...</div></div>;
  }

  return (
    <>
      {!user ? (
        <AuthScreens
          initialActivationToken={initialActivationToken}
          onAuthenticated={(u) => setUser(u)}
          toast={toast}
        />
      ) : (
        <div className="app-shell">
          <aside className="sidebar">
            <div className="sidebar-brand">
              <div className="brand-badge">ATT</div>
              <div>
                <strong>Điểm danh Campus</strong>
                <div className="tiny muted-light">{ROLE_LABELS[user.role] || user.role}</div>
              </div>
            </div>

            <div className="sidebar-user">
              <div className="row-actions">
                <UserCheck size={16} />
                <strong>{user.full_name}</strong>
              </div>
              <div className="tiny muted-light">{user.email}</div>
              {user.school_name && <div className="tiny muted-light">{user.school_name}</div>}
              {user.student_no && <div className="tiny muted-light">MSSV: {user.student_no}</div>}
            </div>

            <div className="sidebar-spacer" />

            <div className="stack">
              <button type="button" className="btn btn-sidebar" onClick={() => setChangePassOpen(true)}>
                <KeyRound size={15} /> Đổi mật khẩu
              </button>
              <button type="button" className="btn btn-sidebar" onClick={handleLogout}>
                <LogOut size={15} /> Đăng xuất
              </button>
            </div>
          </aside>

          <main className="main-area">
            {user.role === 'SYSTEM_ADMIN' && <SystemAdminView toast={toast} />}
            {user.role === 'SCHOOL_ADMIN' && <SchoolAdminView toast={toast} />}
            {user.role === 'LECTURER' && <LecturerView toast={toast} />}
            {user.role === 'STUDENT' && (
              <StudentView
                user={user}
                initialJoinToken={joinToken}
                onClearJoinToken={() => {
                  setJoinToken('');
                  window.history.replaceState({}, '', '/');
                }}
                onRefreshUser={refreshMe}
                toast={toast}
              />
            )}
          </main>

          {(user.must_change_password || changePassOpen) && (
            <ChangePasswordModal
              user={user}
              forced={Boolean(user.must_change_password)}
              onClose={() => setChangePassOpen(false)}
              onLogout={handleLogout}
              onSuccess={async () => {
                setChangePassOpen(false);
                await refreshMe();
                toast('Đổi mật khẩu thành công');
              }}
            />
          )}
        </div>
      )}

      {toastMsg && (
        <div className={`toast ${toastMsg.type === 'err' ? 'toast-err' : 'toast-ok'}`}>
          {toastMsg.msg}
        </div>
      )}
    </>
  );
}

createRoot(document.getElementById('root')).render(<App />);
