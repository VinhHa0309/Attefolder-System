import React, { useEffect, useState } from 'react';
import { apiFetch, downloadFile, formatDateTime } from '../api';
import { Building2, Download, KeyRound, Lock, RefreshCw, Settings, Unlock, Upload, Users } from 'lucide-react';

export function SystemAdminView({ toast }) {
  const [schools, setSchools] = useState([]);
  const [form, setForm] = useState({ code: '', name: '', adminName: '', recoveryEmail: '' });
  const [createdResult, setCreatedResult] = useState(null);
  const [busy, setBusy] = useState(false);

  async function load() {
    const data = await apiFetch('/schools');
    setSchools(data);
  }

  useEffect(() => {
    load().catch((e) => toast(e.message, 'err'));
  }, []);

  async function createSchool(e) {
    e.preventDefault();
    setBusy(true);
    try {
      const res = await apiFetch('/system/schools', { method: 'POST', body: form });
      setCreatedResult(res);
      setForm({ code: '', name: '', adminName: '', recoveryEmail: '' });
      await load();
      toast(`Đã khởi tạo trường ${res.code} và gửi liên kết kích hoạt 24 giờ`);
    } catch (err) {
      toast(err.message, 'err');
    } finally {
      setBusy(false);
    }
  }

  async function reissue(school) {
    if (!confirm(`Cấp lại liên kết kích hoạt Admin cho trường ${school.name} (${school.code})? Liên kết cũ và phiên đăng nhập hiện tại của Admin sẽ bị vô hiệu hóa.`)) return;
    try {
      const res = await apiFetch(`/system/schools/${school.id}/reissue-admin`, { method: 'POST' });
      setCreatedResult({ ...res, code: school.code, name: school.name });
      await load();
      toast('Đã cấp lại liên kết kích hoạt Admin mới');
    } catch (err) {
      toast(err.message, 'err');
    }
  }

  return (
    <div className="stack-lg">
      <div className="card">
        <div className="card-head">
          <h2><Building2 size={20} /> Khởi tạo Trường học & Admin trường</h2>
        </div>
        <p className="muted small">
          Hệ thống không tạo mật khẩu mặc định cho Admin trường — sau khi tạo trường sẽ sinh liên kết kích hoạt dùng 1 lần (hạn 24 giờ) gửi tới email khôi phục.
        </p>
        <form onSubmit={createSchool} className="grid-2" style={{ marginTop: 12 }}>
          <label>
            Mã trường (VD: BKHN, FPTU)
            <input value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} required />
          </label>
          <label>
            Tên trường học
            <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required />
          </label>
          <label>
            Họ tên Quản trị viên trường
            <input value={form.adminName} onChange={(e) => setForm({ ...form, adminName: e.target.value })} required />
          </label>
          <label>
            Email khôi phục / đăng nhập Admin
            <input type="email" value={form.recoveryEmail} onChange={(e) => setForm({ ...form, recoveryEmail: e.target.value })} required />
          </label>
          <div style={{ gridColumn: '1 / -1' }}>
            <button type="submit" className="btn btn-primary" disabled={busy}>
              {busy ? 'Đang khởi tạo...' : 'Khởi tạo trường & Sinh liên kết kích hoạt'}
            </button>
          </div>
        </form>
        {createdResult && (
          <div className="alert alert-info" style={{ marginTop: 14 }}>
            <strong>Liên kết kích hoạt (hạn 24h · {createdResult.code}):</strong>
            {createdResult.activationLink ? (
              <div className="code-copy">
                <code>{createdResult.activationLink}</code>
                <button
                  type="button"
                  className="btn btn-outline btn-sm"
                  onClick={() => { navigator.clipboard.writeText(createdResult.activationLink); toast('Đã sao chép liên kết'); }}
                >
                  Sao chép
                </button>
              </div>
            ) : (
              <div>Đã gửi email tới {createdResult.recoveryEmail}</div>
            )}
          </div>
        )}
      </div>

      <div className="card">
        <div className="card-head">
          <h2>Danh sách các trường trong hệ thống</h2>
          <button type="button" className="btn btn-outline btn-sm" onClick={load}><RefreshCw size={14} /> Tải lại</button>
        </div>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Mã trường</th>
                <th>Tên trường</th>
                <th>Quản trị viên</th>
                <th>Email khôi phục</th>
                <th>Trạng thái Admin</th>
                <th>Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {schools.map((s) => (
                <tr key={s.id}>
                  <td><strong>{s.code}</strong></td>
                  <td>{s.name}</td>
                  <td>{s.admin_name || '—'}</td>
                  <td>{s.recovery_email}</td>
                  <td>
                    <span className={`badge ${s.admin_status === 'ACTIVE' ? 'badge-green' : s.admin_status === 'LOCKED' ? 'badge-red' : 'badge-amber'}`}>
                      {s.admin_status === 'ACTIVE' ? 'Đang hoạt động' : s.admin_status === 'LOCKED' ? 'Bị khóa' : 'Chưa kích hoạt'}
                    </span>
                  </td>
                  <td>
                    <button type="button" className="btn btn-outline btn-sm" onClick={() => reissue(s)}>
                      Cấp lại tài khoản quản trị
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

export function SchoolAdminView({ toast }) {
  const [tab, setTab] = useState('settings');
  const [settings, setSettings] = useState(null);
  const [imports, setImports] = useState([]);
  const [importRes, setImportRes] = useState(null);
  const [accounts, setAccounts] = useState([]);
  const [audits, setAudits] = useState([]);
  const [filterQ, setFilterQ] = useState('');
  const [filterRole, setFilterRole] = useState('');
  const [filterStatus, setFilterStatus] = useState('');
  const [resetCred, setResetCred] = useState(null);
  const [batchDefaultPass, setBatchDefaultPass] = useState('');
  const [busy, setBusy] = useState(false);

  async function loadSettings() {
    const s = await apiFetch('/school/settings');
    setSettings(s);
    const fallback = s?.defaultstudentpassword || s?.defaultStudentPassword || (s?.code ? `${s.code}@1234321` : 'UED@1234321');
    setBatchDefaultPass((prev) => prev || fallback);
  }

  async function loadImports() {
    const list = await apiFetch('/school/students/imports');
    setImports(list);
  }

  async function loadAccounts() {
    const params = new URLSearchParams();
    if (filterQ.trim()) params.set('q', filterQ.trim());
    if (filterRole) params.set('role', filterRole);
    if (filterStatus) params.set('status', filterStatus);
    const list = await apiFetch(`/school/accounts?${params.toString()}`);
    setAccounts(list);
  }

  async function loadAudits() {
    const list = await apiFetch('/school/audit-logs');
    setAudits(list);
  }

  useEffect(() => {
    loadSettings().catch((e) => toast(e.message, 'err'));
    loadImports().catch(() => {});
    loadAccounts().catch(() => {});
    loadAudits().catch(() => {});
  }, []);

  async function saveSettings(e) {
    e.preventDefault();
    setBusy(true);
    try {
      const payload = {
        radiusMeters: Number(settings.radiusmeters ?? settings.radiusMeters),
        radiusMinMeters: Number(settings.radiusminmeters ?? settings.radiusMinMeters),
        radiusMaxMeters: Number(settings.radiusmaxmeters ?? settings.radiusMaxMeters),
        gpsAccuracyMeters: Number(settings.gpsaccuracymeters ?? settings.gpsAccuracyMeters),
        gpsAccuracyMinMeters: Number(settings.gpsaccuracyminmeters ?? settings.gpsAccuracyMinMeters),
        gpsAccuracyMaxMeters: Number(settings.gpsaccuracymaxmeters ?? settings.gpsAccuracyMaxMeters),
        maxDeviationMeters: Number(settings.maxdeviationmeters ?? settings.maxDeviationMeters),
        clusterMadMeters: Number(settings.clustermadmeters ?? settings.clusterMadMeters),
        minClusterRecords: Number(settings.minclusterrecords ?? settings.minClusterRecords),
        reviewHours: Number(settings.reviewhours ?? settings.reviewHours),
        defaultSessionDurationSeconds: Number(settings.defaultsessiondurationseconds ?? settings.defaultSessionDurationSeconds),
        codeIntervalSeconds: Number(settings.codeintervalseconds ?? settings.codeIntervalSeconds),
        defaultStudentPassword: (settings.defaultstudentpassword ?? settings.defaultStudentPassword ?? '').trim(),
        allowedCidrs: String(settings.allowedcidrs ?? settings.allowedCidrs ?? '')
          .split(',')
          .map((x) => x.trim())
          .filter(Boolean),
      };
      await apiFetch('/school/settings', { method: 'PUT', body: payload });
      await loadSettings();
      toast('Đã cập nhật cấu hình trường học');
    } catch (err) {
      toast(err.message, 'err');
    } finally {
      setBusy(false);
    }
  }

  function updateSetting(key, val) {
    setSettings((prev) => ({ ...prev, [key]: val }));
  }

  async function uploadStudents(e) {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    const fd = new FormData();
    fd.append('file', file);
    if (batchDefaultPass.trim()) {
      fd.append('defaultPassword', batchDefaultPass.trim());
    }
    setBusy(true);
    try {
      const res = await apiFetch('/school/students/import', { method: 'POST', formData: fd });
      setImportRes(res);
      await loadImports();
      await loadAccounts();
      toast(`Nạp thành công batch ${res.batchCode}: tạo ${res.created}, bỏ qua ${res.skipped ?? res.updated}`);
    } catch (err) {
      toast(err.message, 'err');
    } finally {
      setBusy(false);
    }
  }

  async function lockUser(acc) {
    const reason = prompt(`Nhập lý do khóa tài khoản ${acc.full_name} (${acc.role === 'LECTURER' ? 'chỉ khóa trong phạm vi trường này' : acc.student_no}):`);
    if (!reason || !reason.trim()) return;
    try {
      await apiFetch(`/school/accounts/${acc.id}/lock`, {
        method: 'POST',
        body: { reason: reason.trim(), confirmActiveSession: false },
      });
      await loadAccounts();
      await loadAudits();
      toast('Đã khóa tài khoản thành công');
    } catch (err) {
      if (err.data?.requiresConfirmation) {
        if (confirm(`${err.message}\nBạn vẫn chắc chắn muốn khóa ngay?`)) {
          await apiFetch(`/school/accounts/${acc.id}/lock`, {
            method: 'POST',
            body: { reason: reason.trim(), confirmActiveSession: true },
          });
          await loadAccounts();
          await loadAudits();
          toast('Đã khóa tài khoản thành công');
        }
      } else {
        toast(err.message, 'err');
      }
    }
  }

  async function unlockUser(acc) {
    const reason = prompt(`Lý do mở khóa tài khoản ${acc.full_name}:`, 'Mở khóa tài khoản');
    if (reason === null) return;
    try {
      await apiFetch(`/school/accounts/${acc.id}/unlock`, {
        method: 'POST',
        body: { reason: reason || 'Mở khóa tài khoản' },
      });
      await loadAccounts();
      await loadAudits();
      toast('Đã mở khóa tài khoản');
    } catch (err) {
      toast(err.message, 'err');
    }
  }

  async function resetStudentPass(acc) {
    const suggested = batchDefaultPass.trim() || (settings?.code ? `${settings.code}@1234321` : 'UED@1234321');
    const customPass = prompt(
      `Nhập mật khẩu tạm cấp lại cho sinh viên ${acc.full_name} (${acc.student_no})\n(Sinh viên phải đổi mật khẩu này ngay ở lần đăng nhập kế tiếp):`,
      suggested,
    );
    if (!customPass || !customPass.trim()) return;
    try {
      const res = await apiFetch(`/school/accounts/${acc.id}/reset-password`, {
        method: 'POST',
        body: { reason: 'Cấp lại mật khẩu theo yêu cầu', temporaryPassword: customPass.trim() },
      });
      setResetCred(res);
      await loadAccounts();
      await loadAudits();
      toast(`Đã cấp lại mật khẩu cho ${acc.student_no}: ${res.temporaryPassword}`);
    } catch (err) {
      toast(err.message, 'err');
    }
  }

  return (
    <div className="stack-lg">
      <div className="tab-bar">
        <button type="button" className={tab === 'settings' ? 'tab active' : 'tab'} onClick={() => setTab('settings')}>
          <Settings size={15} /> Cấu hình trường
        </button>
        <button type="button" className={tab === 'import' ? 'tab active' : 'tab'} onClick={() => setTab('import')}>
          <Upload size={15} /> Cấp tài khoản sinh viên
        </button>
        <button type="button" className={tab === 'accounts' ? 'tab active' : 'tab'} onClick={() => { setTab('accounts'); loadAccounts(); }}>
          <Users size={15} /> Quản lý tài khoản ({accounts.length})
        </button>
        <button type="button" className={tab === 'audit' ? 'tab active' : 'tab'} onClick={() => { setTab('audit'); loadAudits(); }}>
          Nhật ký tài khoản
        </button>
      </div>

      {tab === 'settings' && settings && (
        <div className="card">
          <div className="card-head">
            <h2>Cấu hình thông số hệ thống · {settings.name} ({settings.code})</h2>
          </div>
          <form onSubmit={saveSettings} className="grid-3">
            <label>
              Bán kính mặc định (m)
              <input type="number" value={settings.radiusmeters ?? settings.radiusMeters ?? 50}
                onChange={(e) => { updateSetting('radiusmeters', e.target.value); updateSetting('radiusMeters', e.target.value); }} />
            </label>
            <label>
              Biên bán kính tối thiểu (m)
              <input type="number" value={settings.radiusminmeters ?? settings.radiusMinMeters ?? 10}
                onChange={(e) => { updateSetting('radiusminmeters', e.target.value); updateSetting('radiusMinMeters', e.target.value); }} />
            </label>
            <label>
              Biên bán kính tối đa (m)
              <input type="number" value={settings.radiusmaxmeters ?? settings.radiusMaxMeters ?? 300}
                onChange={(e) => { updateSetting('radiusmaxmeters', e.target.value); updateSetting('radiusMaxMeters', e.target.value); }} />
            </label>

            <label>
              Sai số GPS mặc định (m)
              <input type="number" value={settings.gpsaccuracymeters ?? settings.gpsAccuracyMeters ?? 30}
                onChange={(e) => { updateSetting('gpsaccuracymeters', e.target.value); updateSetting('gpsAccuracyMeters', e.target.value); }} />
            </label>
            <label>
              Biên sai số GPS tối thiểu (m)
              <input type="number" value={settings.gpsaccuracyminmeters ?? settings.gpsAccuracyMinMeters ?? 5}
                onChange={(e) => { updateSetting('gpsaccuracyminmeters', e.target.value); updateSetting('gpsAccuracyMinMeters', e.target.value); }} />
            </label>
            <label>
              Biên sai số GPS tối đa (m)
              <input type="number" value={settings.gpsaccuracymaxmeters ?? settings.gpsAccuracyMaxMeters ?? 150}
                onChange={(e) => { updateSetting('gpsaccuracymaxmeters', e.target.value); updateSetting('gpsAccuracyMaxMeters', e.target.value); }} />
            </label>

            <label>
              Ngưỡng lệch tối đa (m)
              <input type="number" value={settings.maxdeviationmeters ?? settings.maxDeviationMeters ?? 500}
                onChange={(e) => { updateSetting('maxdeviationmeters', e.target.value); updateSetting('maxDeviationMeters', e.target.value); }} />
            </label>
            <label>
              Ngưỡng phân tán MAD (m)
              <input type="number" value={settings.clustermadmeters ?? settings.clusterMadMeters ?? 30}
                onChange={(e) => { updateSetting('clustermadmeters', e.target.value); updateSetting('clusterMadMeters', e.target.value); }} />
            </label>
            <label>
              Số bản ghi tối thiểu phân tích cụm
              <input type="number" value={settings.minclusterrecords ?? settings.minClusterRecords ?? 3}
                onChange={(e) => { updateSetting('minclusterrecords', e.target.value); updateSetting('minClusterRecords', e.target.value); }} />
            </label>

            <label>
              Thời hạn đối soát (giờ)
              <input type="number" value={settings.reviewhours ?? settings.reviewHours ?? 24}
                onChange={(e) => { updateSetting('reviewhours', e.target.value); updateSetting('reviewHours', e.target.value); }} />
            </label>
            <label>
              Thời lượng phiên mặc định (giây)
              <input type="number" value={settings.defaultsessiondurationseconds ?? settings.defaultSessionDurationSeconds ?? 300}
                onChange={(e) => { updateSetting('defaultsessiondurationseconds', e.target.value); updateSetting('defaultSessionDurationSeconds', e.target.value); }} />
            </label>
            <label>
              Chu kỳ làm mới mã QR/PIN (giây)
              <input type="number" value={settings.codeintervalseconds ?? settings.codeIntervalSeconds ?? 15}
                onChange={(e) => { updateSetting('codeintervalseconds', e.target.value); updateSetting('codeIntervalSeconds', e.target.value); }} />
            </label>

            <label style={{ gridColumn: '1 / -1' }}>
              Mật khẩu mặc định chung cho sinh viên mới (để trống = {settings.code}@1234321)
              <input
                value={settings.defaultstudentpassword ?? settings.defaultStudentPassword ?? ''}
                onChange={(e) => { updateSetting('defaultstudentpassword', e.target.value); updateSetting('defaultStudentPassword', e.target.value); setBatchDefaultPass(e.target.value || `${settings.code}@1234321`); }}
                placeholder={`VD: ${settings.code}@1234321`}
              />
            </label>

            <label style={{ gridColumn: '1 / -1' }}>
              Danh sách dải IP công cộng của trường (CIDR, ngăn cách bằng dấu phẩy)
              <input
                value={settings.allowedcidrs ?? settings.allowedCidrs ?? ''}
                onChange={(e) => { updateSetting('allowedcidrs', e.target.value); updateSetting('allowedCidrs', e.target.value); }}
                placeholder="10.0.0.0/8,192.168.0.0/16"
              />
            </label>

            <div style={{ gridColumn: '1 / -1' }}>
              <button type="submit" className="btn btn-primary" disabled={busy}>
                {busy ? 'Đang lưu...' : 'Lưu cấu hình trường'}
              </button>
            </div>
          </form>
        </div>
      )}

      {tab === 'import' && (
        <div className="stack-lg">
          <div className="card">
            <div className="card-head">
              <h2>Nạp danh sách định danh & Cấp tài khoản sinh viên hàng loạt</h2>
              <button
                type="button"
                className="btn btn-outline btn-sm"
                onClick={() => downloadFile('/school/students/template.xlsx', 'students-import-template.xlsx').catch((e) => toast(e.message, 'err'))}
              >
                <Download size={14} /> Tải mẫu Excel
              </button>
            </div>
            <p className="muted small">
              Hỗ trợ cả mẫu chuẩn (<strong>MSSV, Họ tên, Email trường, Ngày sinh</strong>) lẫn danh sách xuất từ cổng đào tạo trường (tự bỏ qua các dòng tiêu đề trường ở trên, hỗ trợ <strong>Mã SV / MSSV</strong>, <strong>Họ và tên</strong> hoặc tách <strong>Họ + Tên</strong>, ngày sinh <code>dd/MM/yyyy</code>; nếu file chưa có cột Email hệ thống sẽ tự sinh <code>mssv@matruong.edu.vn</code>).
            </p>
            <div className="filter-bar" style={{ marginTop: 10 }}>
              <label style={{ flex: 1, maxWidth: 380 }}>
                Mật khẩu mặc định chung cho sinh viên tạo mới (tối thiểu 6 ký tự)
                <input
                  value={batchDefaultPass}
                  onChange={(e) => setBatchDefaultPass(e.target.value)}
                  placeholder="VD: UED@1234321"
                />
              </label>
              <label className="btn btn-primary" style={{ alignSelf: 'flex-end', cursor: 'pointer' }}>
                <Upload size={15} /> Chọn file Excel nạp danh sách
                <input type="file" accept=".xlsx,.xls" hidden onChange={uploadStudents} disabled={busy} />
              </label>
            </div>

            {importRes && (
              <div className="stack" style={{ marginTop: 16 }}>
                <div className="alert alert-info">
                  <div>
                    Mã lần nạp: <strong>{importRes.batchCode}</strong> · Tạo mới: <strong>{importRes.created}</strong> · Bỏ qua (đã có tài khoản): <strong>{importRes.skipped ?? importRes.updated}</strong> · Lỗi: <strong>{importRes.errors?.length || 0}</strong>
                    {importRes.generatedEmails > 0 && ` · Tự sinh email mặc định: ${importRes.generatedEmails}`}
                  </div>
                  {importRes.defaultPassword && (
                    <div>
                      Mật khẩu ban đầu dùng chung cho toàn bộ sinh viên mới: <code>{importRes.defaultPassword}</code> (bắt buộc đổi ở lần đăng nhập đầu tiên)
                    </div>
                  )}
                </div>
                {importRes.initialCredentials?.length > 0 && (
                  <div className="table-wrap">
                    <table>
                      <thead>
                        <tr><th>MSSV</th><th>Họ tên</th><th>Email đăng nhập/khôi phục</th><th>Mật khẩu tạm ban đầu</th></tr>
                      </thead>
                      <tbody>
                        {importRes.initialCredentials.map((c) => (
                          <tr key={c.studentNo}>
                            <td><strong>{c.studentNo}</strong></td>
                            <td>{c.fullName}</td>
                            <td>{c.email || '—'}</td>
                            <td><code>{c.temporaryPassword}</code></td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
                {importRes.errors?.length > 0 && (
                  <div className="alert alert-err">
                    {importRes.errors.map((e, idx) => <div key={idx}>Dòng {e.row}: {e.error}</div>)}
                  </div>
                )}
              </div>
            )}
          </div>

          <div className="card">
            <h3>Lịch sử các lần nạp danh sách</h3>
            <div className="table-wrap" style={{ marginTop: 10 }}>
              <table>
                <thead>
                  <tr><th>Mã lần nạp</th><th>Thời điểm nạp</th><th>Người thực hiện</th><th>Tổng dòng</th><th>Tạo mới</th><th>Bỏ qua</th><th>Lỗi</th></tr>
                </thead>
                <tbody>
                  {imports.map((b) => (
                    <tr key={b.id}>
                      <td><code>{b.batch_code}</code></td>
                      <td>{formatDateTime(b.created_at)}</td>
                      <td>{b.imported_by_name}</td>
                      <td>{b.total_rows}</td>
                      <td>{b.created_count}</td>
                      <td>{b.updated_count}</td>
                      <td>{b.error_count}</td>
                    </tr>
                  ))}
                  {imports.length === 0 && <tr><td colSpan={7} className="muted">Chưa có lần nạp nào.</td></tr>}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {tab === 'accounts' && (
        <div className="card">
          <div className="card-head">
            <h2>Tra cứu & Quản lý tài khoản</h2>
          </div>
          <div className="filter-bar">
            <input placeholder="Tìm MSSV, họ tên hoặc email..." value={filterQ} onChange={(e) => setFilterQ(e.target.value)} />
            <select value={filterRole} onChange={(e) => setFilterRole(e.target.value)}>
              <option value="">Tất cả vai trò</option>
              <option value="STUDENT">Sinh viên</option>
              <option value="LECTURER">Giảng viên (trong trường)</option>
            </select>
            <select value={filterStatus} onChange={(e) => setFilterStatus(e.target.value)}>
              <option value="">Tất cả trạng thái</option>
              <option value="ACTIVE">Đang hoạt động</option>
              <option value="LOCKED">Bị khóa</option>
            </select>
            <button type="button" className="btn btn-primary btn-sm" onClick={loadAccounts}>Tìm kiếm</button>
          </div>

          {resetCred && (
            <div className="alert alert-info" style={{ marginTop: 10 }}>
              Mật khẩu tạm mới cho MSSV <strong>{resetCred.studentNo}</strong>: <code>{resetCred.temporaryPassword}</code>
            </div>
          )}

          <div className="table-wrap" style={{ marginTop: 12 }}>
            <table>
              <thead>
                <tr>
                  <th>Vai trò</th>
                  <th>MSSV</th>
                  <th>Họ tên</th>
                  <th>Email</th>
                  <th>Vi phạm</th>
                  <th>Thiết bị gốc</th>
                  <th>Trạng thái</th>
                  <th>Thao tác</th>
                </tr>
              </thead>
              <tbody>
                {accounts.map((a) => (
                  <tr key={`${a.role}-${a.id}`}>
                    <td><span className="badge badge-slate">{a.role === 'STUDENT' ? 'Sinh viên' : 'Giảng viên'}</span></td>
                    <td>{a.student_no || '—'}</td>
                    <td>{a.full_name}</td>
                    <td>{a.email}</td>
                    <td>{a.violation_count || 0}</td>
                    <td>{a.device_bound_at ? 'Đã gắn' : 'Chưa gắn'}</td>
                    <td>
                      <span className={`badge ${a.effective_status === 'ACTIVE' ? 'badge-green' : 'badge-red'}`}>
                        {a.effective_status === 'ACTIVE' ? 'Đang hoạt động' : 'Bị khóa'}
                      </span>
                      {a.lock_reason && <div className="muted tiny">{a.lock_reason}</div>}
                    </td>
                    <td>
                      <div className="row-actions">
                        {a.effective_status === 'ACTIVE' ? (
                          <button type="button" className="btn btn-outline btn-sm" onClick={() => lockUser(a)}>
                            <Lock size={13} /> Khóa
                          </button>
                        ) : (
                          <button type="button" className="btn btn-outline btn-sm" onClick={() => unlockUser(a)}>
                            <Unlock size={13} /> Mở khóa
                          </button>
                        )}
                        {a.role === 'STUDENT' && (
                          <button type="button" className="btn btn-outline btn-sm" onClick={() => resetStudentPass(a)}>
                            <KeyRound size={13} /> Cấp lại MK
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {tab === 'audit' && (
        <div className="card">
          <h3>Nhật ký thao tác tài khoản của trường</h3>
          <div className="table-wrap" style={{ marginTop: 10 }}>
            <table>
              <thead>
                <tr><th>Thời gian</th><th>Hành động</th><th>Đối tượng</th><th>Người thực hiện</th><th>Lý do</th></tr>
              </thead>
              <tbody>
                {audits.map((l) => (
                  <tr key={l.id}>
                    <td>{formatDateTime(l.created_at)}</td>
                    <td><code>{l.action}</code></td>
                    <td>{l.target_name || '—'} {l.target_student_no ? `(${l.target_student_no})` : ''}</td>
                    <td>{l.actor_name}</td>
                    <td>{l.reason || '—'}</td>
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
