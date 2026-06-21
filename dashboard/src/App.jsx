import { useEffect, useMemo, useRef, useState } from "react";

/* ArtGuard surveillance control dashboard.
   Connects to the gateway's /ws/alerts WebSocket and /api/* REST. If the
   backend isn't reachable, a built-in demo feed drives the UI so the dashboard
   is viewable standalone (clearly badged "DEMO FEED"). */

const CAMERAS = [
  { id: "cam-01", name: "Gallery A — Mona Lisa", zone: "WEST WING" },
  { id: "cam-02", name: "Gallery B — Sculptures", zone: "WEST WING" },
  { id: "cam-03", name: "East Entrance", zone: "PERIMETER" },
];

const SEVERITY = {
  knife:    { rank: 3, tag: "WEAPON",   color: "var(--red)" },
  scissors: { rank: 3, tag: "WEAPON",   color: "var(--red)" },
  backpack: { rank: 2, tag: "UNATTEND", color: "var(--amber)" },
  suitcase: { rank: 2, tag: "UNATTEND", color: "var(--amber)" },
  person:   { rank: 1, tag: "PRESENCE", color: "var(--blue)" },
};
const sevOf = (label) => SEVERITY[label] || { rank: 1, tag: "DETECT", color: "var(--blue)" };

function useClock() {
  const [t, setT] = useState(new Date());
  useEffect(() => {
    const i = setInterval(() => setT(new Date()), 1000);
    return () => clearInterval(i);
  }, []);
  return t;
}

export default function App() {
  const [alerts, setAlerts] = useState([]);
  const [connected, setConnected] = useState(false);
  const [demo, setDemo] = useState(false);
  const seq = useRef(1);
  const clock = useClock();

  // try the real WebSocket; fall back to a demo generator
  useEffect(() => {
    let ws, demoTimer, retry;
    let alive = true;

    const pushAlert = (a) =>
      setAlerts((prev) => [{ ...a, _k: seq.current++ }, ...prev].slice(0, 60));

    const startDemo = () => {
      if (!alive || demoTimer) return;
      setDemo(true);
      const labels = ["person", "person", "person", "backpack", "knife", "suitcase"];
      demoTimer = setInterval(() => {
        const cam = CAMERAS[Math.floor(Math.random() * CAMERAS.length)];
        const label = labels[Math.floor(Math.random() * labels.length)];
        pushAlert({
          incidentId: 1000 + seq.current,
          cameraId: cam.id,
          cameraName: cam.name,
          label,
          confidence: 0.55 + Math.random() * 0.44,
          status: Math.random() < 0.4 ? "CORROBORATED" : "OPENED",
          detectionCount: 1 + Math.floor(Math.random() * 5),
          latencyMs: 70 + Math.floor(Math.random() * 110),
          timestamp: new Date().toISOString(),
        });
      }, 1400 + Math.random() * 1200);
    };

    const connect = () => {
      try {
        const proto = location.protocol === "https:" ? "wss" : "ws";
        ws = new WebSocket(`${proto}://${location.host}/ws/alerts`);
      } catch {
        startDemo();
        return;
      }
      ws.onopen = () => { if (!alive) return; setConnected(true); setDemo(false); clearInterval(demoTimer); demoTimer = null; };
      ws.onmessage = (e) => { try { pushAlert(JSON.parse(e.data)); } catch {} };
      ws.onclose = () => { if (!alive) return; setConnected(false); startDemo(); retry = setTimeout(connect, 4000); };
      ws.onerror = () => { try { ws.close(); } catch {} };
    };

    connect();
    // if no connection within 1.2s, kick off the demo so the UI is alive
    const demoKick = setTimeout(() => { if (!connected) startDemo(); }, 1200);

    return () => {
      alive = false;
      clearTimeout(demoKick); clearTimeout(retry); clearInterval(demoTimer);
      if (ws) { ws.onclose = null; ws.close(); }
    };
  }, []); // eslint-disable-line

  // derived stats
  const stats = useMemo(() => {
    const recent = alerts.slice(0, 40);
    const lat = recent.map((a) => a.latencyMs).filter((x) => x != null).sort((a, b) => a - b);
    const p95 = lat.length ? lat[Math.min(lat.length - 1, Math.floor(lat.length * 0.95))] : null;
    const openIds = new Set(alerts.filter((a) => a.status !== "RESOLVED").map((a) => a.incidentId));
    return { p95, open: openIds.size, total: alerts.length };
  }, [alerts]);

  // last alert per camera (for the grid status)
  const lastByCam = useMemo(() => {
    const m = {};
    for (const a of alerts) if (!m[a.cameraId]) m[a.cameraId] = a;
    return m;
  }, [alerts]);

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark" />
          <div>
            <div className="brand-name">ARTGUARD</div>
            <div className="brand-sub">Surveillance Control</div>
          </div>
        </div>

        <div className="pipeline">
          {["RTSP", "Kafka", "YOLOv8", "Incident", "Alert"].map((s, i) => (
            <span key={s} className="pipe-node">
              {s}{i < 4 && <i className="pipe-arrow" />}
            </span>
          ))}
        </div>

        <div className="status-cluster">
          <div className={`conn ${connected ? "live" : demo ? "demo" : "down"}`}>
            <span className="dot" />
            {connected ? "GATEWAY LIVE" : demo ? "DEMO FEED" : "CONNECTING"}
          </div>
          <span className="clock">{clock.toLocaleTimeString("en-US", { hour12: false })}</span>
        </div>
      </header>

      <div className="statbar">
        <Stat label="Cameras" value={CAMERAS.length} />
        <Stat label="Open incidents" value={stats.open} accent={stats.open ? "var(--amber)" : undefined} />
        <Stat label="Alerts (session)" value={stats.total} />
        <Stat label="p95 latency" value={stats.p95 != null ? `${stats.p95} ms` : "—"}
              accent={stats.p95 != null && stats.p95 < 200 ? "var(--green)" : "var(--amber)"}
              hint={stats.p95 != null && stats.p95 < 200 ? "within 200ms SLO" : "SLO 200ms"} />
      </div>

      <main className="grid">
        <section className="cameras">
          <div className="section-head">CAMERA FEEDS</div>
          <div className="cam-grid">
            {CAMERAS.map((cam) => {
              const last = lastByCam[cam.id];
              const sev = last ? sevOf(last.label) : null;
              return (
                <div key={cam.id} className="cam-card">
                  <div className="cam-view">
                    <div className="scanline" />
                    <div className="cam-grain" />
                    {last && (
                      <div className="cam-box" style={{ borderColor: sev.color }}>
                        <span className="cam-box-tag" style={{ background: sev.color }}>
                          {last.label} {(last.confidence * 100).toFixed(0)}%
                        </span>
                      </div>
                    )}
                    <div className="cam-rec"><span className="rec-dot" />REC</div>
                    <div className="cam-zone">{cam.zone}</div>
                  </div>
                  <div className="cam-meta">
                    <span className="cam-name">{cam.name}</span>
                    <span className="cam-id">{cam.id}</span>
                  </div>
                </div>
              );
            })}
          </div>
        </section>

        <section className="feed">
          <div className="section-head">
            LIVE ALERTS
            <span className="feed-count">{alerts.length}</span>
          </div>
          <div className="alert-list">
            {alerts.length === 0 && <div className="empty">Awaiting detections…</div>}
            {alerts.map((a) => {
              const sev = sevOf(a.label);
              return (
                <div key={a._k} className="alert" style={{ "--sev": sev.color }}>
                  <div className="alert-sev" style={{ background: sev.color }}>{sev.tag}</div>
                  <div className="alert-main">
                    <div className="alert-line1">
                      <span className="alert-label">{a.label}</span>
                      <span className="alert-conf">{(a.confidence * 100).toFixed(0)}%</span>
                      {a.status === "CORROBORATED" && <span className="alert-corr">×{a.detectionCount}</span>}
                    </div>
                    <div className="alert-line2">
                      <span className="alert-cam">{a.cameraName}</span>
                      <span className="alert-time">
                        {new Date(a.timestamp).toLocaleTimeString("en-US", { hour12: false })}
                      </span>
                    </div>
                  </div>
                  <div className={`alert-lat ${a.latencyMs < 200 ? "ok" : "warn"}`}>
                    {a.latencyMs}<span>ms</span>
                  </div>
                </div>
              );
            })}
          </div>
        </section>
      </main>
    </div>
  );
}

function Stat({ label, value, accent, hint }) {
  return (
    <div className="stat">
      <div className="stat-value" style={accent ? { color: accent } : undefined}>{value}</div>
      <div className="stat-label">{label}{hint && <span className="stat-hint"> · {hint}</span>}</div>
    </div>
  );
}
