const TOKEN_KEY = 'att_token';
const DEVICE_KEY = 'att_device_fp';

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || '';
}

export function setToken(token) {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token);
  } else {
    localStorage.removeItem(TOKEN_KEY);
  }
}

export function getDeviceFingerprint() {
  let id = localStorage.getItem(DEVICE_KEY);
  if (!id) {
    const raw = [
      crypto.randomUUID(),
      navigator.userAgent,
      navigator.language,
      screen.width + 'x' + screen.height,
      Intl.DateTimeFormat().resolvedOptions().timeZone || '',
    ].join('|');
    id = btoa(unescape(encodeURIComponent(raw))).slice(0, 96);
    localStorage.setItem(DEVICE_KEY, id);
  }
  return id;
}

export async function apiFetch(path, { method = 'GET', body, formData, blob = false } = {}) {
  const headers = {};
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;

  let payload;
  if (formData) {
    payload = formData;
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }

  const res = await fetch(`/api${path}`, { method, headers, body: payload });
  if (blob) {
    if (!res.ok) {
      let msg = 'Không thể tải tệp';
      try {
        const j = await res.json();
        msg = j.error || msg;
      } catch {
        // ignore parse failure
      }
      throw new Error(msg);
    }
    return res.blob();
  }

  const text = await res.text();
  let data = {};
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = { raw: text };
    }
  }
  if (!res.ok) {
    const err = new Error(data.error || `Lỗi máy chủ (${res.status})`);
    err.status = res.status;
    err.code = data.code;
    err.data = data;
    throw err;
  }
  return data;
}

export async function downloadFile(path, fallbackFilename) {
  const headers = {};
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(`/api${path}`, { headers });
  if (!res.ok) {
    let msg = 'Xuất báo cáo thất bại';
    try {
      const j = await res.json();
      msg = j.error || msg;
    } catch {
      // ignore
    }
    throw new Error(msg);
  }
  const cd = res.headers.get('Content-Disposition') || '';
  const match = cd.match(/filename\*=UTF-8''([^;]+)|filename="([^"]+)"/i);
  const filename = match ? decodeURIComponent(match[1] || match[2]) : fallbackFilename;
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

export function formatDateTime(v) {
  if (!v) return '—';
  const d = new Date(v);
  if (Number.isNaN(d.getTime())) return String(v);
  return d.toLocaleString('vi-VN', {
    hour12: false,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

export function formatCountdown(seconds) {
  const s = Math.max(0, Math.floor(seconds));
  const m = Math.floor(s / 60);
  const r = s % 60;
  return `${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}`;
}

export const FLAG_LABELS = {
  SHARED_DEVICE: 'Nghi vấn gian lận thiết bị (trùng tài khoản)',
  NEW_DEVICE: 'Thiết bị mới',
  LOCATION_DENIED: 'Từ chối quyền vị trí',
  LOCATION_UNAVAILABLE: 'Không lấy được tín hiệu GPS',
  GPS_LOW_ACCURACY: 'Sai số GPS vượt ngưỡng',
  OUTSIDE_RADIUS: 'Ngoài bán kính hợp lệ',
  CENTER_SHIFTED: 'Tâm điểm đã được hệ thống dịch',
  CENTER_SUSPECT: 'Nghi vấn tâm điểm lệch',
  MANUAL: 'Điểm danh thủ công',
};

export const RESULT_META = {
  PRESENT: { label: 'Có mặt', cls: 'badge-green' },
  REVIEW: { label: 'Chờ đối chiếu', cls: 'badge-amber' },
  PENDING: { label: 'Chờ duyệt', cls: 'badge-orange' },
  ABSENT: { label: 'Vắng mặt', cls: 'badge-red' },
};

export const SESSION_STATUS_META = {
  OPEN: { label: 'Đang mở', cls: 'badge-green' },
  RECONCILING: { label: 'Đang đối soát', cls: 'badge-amber' },
  FINALIZED: { label: 'Đã chốt', cls: 'badge-slate' },
};
