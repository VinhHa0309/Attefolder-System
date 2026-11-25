import React, { useEffect, useRef } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';

export default function MapPicker({ lat, lng, radiusMeters = 50, onChange }) {
  const containerRef = useRef(null);
  const mapRef = useRef(null);
  const markerRef = useRef(null);
  const circleRef = useRef(null);

  const initialLat = typeof lat === 'number' && !Number.isNaN(lat) ? lat : 21.0285;
  const initialLng = typeof lng === 'number' && !Number.isNaN(lng) ? lng : 105.8542;

  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;
    const map = L.map(containerRef.current, {
      center: [initialLat, initialLng],
      zoom: 17,
    });
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; OpenStreetMap',
      maxZoom: 20,
    }).addTo(map);

    map.on('click', (e) => {
      onChange?.(Number(e.latlng.lat.toFixed(6)), Number(e.latlng.lng.toFixed(6)));
    });

    mapRef.current = map;
    setTimeout(() => map.invalidateSize(), 200);

    return () => {
      map.remove();
      mapRef.current = null;
      markerRef.current = null;
      circleRef.current = null;
    };
  }, []);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    if (typeof lat !== 'number' || typeof lng !== 'number' || Number.isNaN(lat) || Number.isNaN(lng)) {
      if (markerRef.current) {
        markerRef.current.remove();
        markerRef.current = null;
      }
      if (circleRef.current) {
        circleRef.current.remove();
        circleRef.current = null;
      }
      return;
    }

    const pos = [lat, lng];
    if (!markerRef.current) {
      markerRef.current = L.circleMarker(pos, {
        radius: 8,
        color: '#0f3d32',
        weight: 2,
        fillColor: '#1f7a64',
        fillOpacity: 1,
      }).addTo(map);
    } else {
      markerRef.current.setLatLng(pos);
    }

    if (!circleRef.current) {
      circleRef.current = L.circle(pos, {
        radius: Number(radiusMeters) || 50,
        color: '#174b3e',
        weight: 1.5,
        fillColor: '#2ea888',
        fillOpacity: 0.16,
      }).addTo(map);
    } else {
      circleRef.current.setLatLng(pos);
      circleRef.current.setRadius(Number(radiusMeters) || 50);
    }

    map.panTo(pos, { animate: true });
  }, [lat, lng, radiusMeters]);

  return (
    <div className="map-picker-wrap">
      <div ref={containerRef} className="map-canvas" />
      <div className="map-hint">
        Nhấp lên bản đồ để chọn tọa độ phòng học (nếu offline vẫn nhấp chọn hoặc nhập kinh độ/vĩ độ thủ công bên dưới).
      </div>
    </div>
  );
}
