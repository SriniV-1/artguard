import { useEffect, useMemo, useRef, useState } from "react";

/* ArtGuard surveillance control — overhead simulation.
   Each zone is a top-down view; people are green dots that turn red when they
   trigger a suspicious-behavior alert. Scene state + alerts stream from the
   gateway over /ws/alerts (envelopes tagged {type:"scene"|"alert"}). A
   self-contained demo feed drives the UI if the backend isn't reachable. */

const FALLBACK_ZONES = [
  { id: "cam-01", name: "West Concourse" },
  { id: "cam-02", name: "Main Gallery" },
  { id: "cam-03", name: "North Entrance" },
  { id: "cam-04", name: "Loading Dock" },
];

const SEV = {
  "Intrusion":           { tag: "INTRUSION", color: "var(--red)" },
  "Abandoned Object":    { tag: "OBJECT",    color: "var(--amber)" },
  "Erratic Movement":    { tag: "ERRATIC",   color: "var(--amber)" },
  "Suspicious Movement": { tag: "SUSPECT",   color: "var(--blue)" },
  "Loitering":           { tag: "LOITER",    color: "var(--blue)" },
};
const sevOf = (b) => SEV[b] || { tag: "EVENT", color: "var(--blue)" };

function useClock() {
  const [t, setT] = useState(new Date());
  useEffect(() => { const i = setInterval(() => setT(new Date()), 1000); return () => clearInterval(i); }, []);
  return t;
}

export default function App() {
  const [scene, setScene] = useState({ cameras: [] });
  const [alerts, setAlerts] = useState([]);
  const [connected, setConnected] = useState(false);
  const [demo, setDemo] = useState(false);
  const [paused, setPaused] = useState(false);
  const [selected, setSelected] = useState(null);
  const pausedRef = useRef(false);
  const connectedRef = useRef(false);
  const seq = useRef(1);
  const clock = useClock();
  pausedRef.current = paused;

  useEffect(() => {
    let ws, sim, retry, alive = true;

    const onAlert = (a) => {
      if (pausedRef.current) return;
      setAlerts((prev) => [{ ...a, _k: seq.current++ }, ...prev].slice(0, 50));
    };

    // ── demo simulation (frontend) — ONLY when the backend is unreachable ──
    const startDemo = () => {
      if (!alive || sim || connectedRef.current) return; // never run alongside the live WS
      setDemo(true);
      const zones = FALLBACK_ZONES.map((z) => ({
        ...z, people: Array.from({ length: 7 }, (_, i) => ({
          id: i, x: Math.random(), y: Math.random(),
          tx: Math.random(), ty: Math.random(), status: "normal", until: 0,
        })),
      }));
      const behaviors = ["Loitering","Loitering","Suspicious Movement","Suspicious Movement","Erratic Movement","Intrusion","Abandoned Object"];
      sim = setInterval(() => {
        const now = Date.now();
        for (const z of zones) for (const p of z.people) {
          const dx = p.tx - p.x, dy = p.ty - p.y, d = Math.hypot(dx, dy);
          if (d < 0.02) { p.tx = Math.random(); p.ty = Math.random(); }
          else { p.x += dx / d * 0.008; p.y += dy / d * 0.008; }
          if (p.status === "alert" && now > p.until) p.status = "normal";
          if (p.status === "normal" && Math.random() < 0.01) {
            p.status = "alert"; p.until = now + 6000;
            const b = behaviors[Math.floor(Math.random() * behaviors.length)];
            onAlert({ incidentId: 1000 + seq.current, cameraId: z.id, cameraName: z.name,
              label: b, confidence: 0.6 + Math.random() * 0.39, status: "OPENED",
              detectionCount: 1, latencyMs: 60 + Math.floor(Math.random() * 90),
              timestamp: new Date().toISOString() });
          }
        }
        setScene({ cameras: zones.map((z) => ({ id: z.id, name: z.name,
          people: z.people.map((p) => ({ id: p.id, x: p.x, y: p.y, status: p.status })) })) });
      }, 100);
    };

    const connect = () => {
      try {
        const proto = location.protocol === "https:" ? "wss" : "ws";
        ws = new WebSocket(`${proto}://${location.host}/ws/alerts`);
      } catch { startDemo(); return; }
      ws.onopen = () => {
        if (!alive) return;
        connectedRef.current = true; setConnected(true); setDemo(false);
        clearInterval(sim); sim = null;          // stop any demo feed once live
        clearTimeout(kick);
      };
      ws.onmessage = (e) => {
        try {
          const m = JSON.parse(e.data);
          if (m.type === "scene") setScene(m.data);
          else if (m.type === "alert") onAlert(m.data);
        } catch {}
      };
      ws.onclose = () => {
        if (!alive) return;
        connectedRef.current = false; setConnected(false);
        startDemo(); retry = setTimeout(connect, 4000);
      };
      ws.onerror = () => { try { ws.close(); } catch {} };
    };

    connect();
    // only fall back to the demo if we haven't connected within 1.5s
    const kick = setTimeout(() => { if (!connectedRef.current) startDemo(); }, 1500);
    return () => { alive = false; clearTimeout(kick); clearTimeout(retry); clearInterval(sim); if (ws) { ws.onclose = null; ws.close(); } };
  }, []); // eslint-disable-line

  const stats = useMemo(() => {
    const lat = alerts.slice(0, 40).map((a) => a.latencyMs).filter((x) => x != null).sort((a, b) => a - b);
    const p95 = lat.length ? lat[Math.min(lat.length - 1, Math.floor(lat.length * 0.95))] : null;
    const open = new Set(alerts.filter((a) => a.status !== "RESOLVED").map((a) => a.incidentId)).size;
    const tracked = (scene.cameras || []).reduce((s, c) => s + (c.people?.length || 0), 0);
    return { p95, open, total: alerts.length, tracked };
  }, [alerts, scene]);

  // which people are currently flagged (for the red dots), by camera
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
          {["Tracking", "Kafka", "Analysis", "Incident", "Alert"].map((s, i) => (
            <span key={s} className="pipe-node">{s}{i < 4 && <i className="pipe-arrow" />}</span>
          ))}
        </div>
        <div className="status-cluster">
          <div className={`conn ${connected ? "live" : demo ? "demo" : "down"}`}>
            <span className="dot" />{connected ? "GATEWAY LIVE" : demo ? "DEMO FEED" : "CONNECTING"}
          </div>
          <span className="clock">{clock.toLocaleTimeString("en-US", { hour12: false })}</span>
        </div>
      </header>

      <div className="statbar">
        <Stat label="Zones" value={(scene.cameras || []).length || FALLBACK_ZONES.length} />
        <Stat label="People tracked" value={stats.tracked} />
        <Stat label="Open incidents" value={stats.open} accent={stats.open ? "var(--amber)" : undefined} />
        <Stat label="p95 latency" value={stats.p95 != null ? `${stats.p95} ms` : "—"}
              accent={stats.p95 != null && stats.p95 < 200 ? "var(--green)" : "var(--amber)"}
              hint={stats.p95 != null && stats.p95 < 200 ? "within 200ms SLO" : "SLO 200ms"} />
      </div>

      <main className="grid">
        <section className="cameras">
          <div className="section-head">ZONE OVERVIEW <span className="legend"><i className="lg-dot normal" />tracked<i className="lg-dot alert" />flagged</span></div>
          <div className="zone-grid">
            {(scene.cameras?.length ? scene.cameras : FALLBACK_ZONES.map((z) => ({ ...z, people: [] }))).map((z) => (
              <Zone key={z.id} zone={z} />
            ))}
          </div>
        </section>

        <section className="feed">
          <div className="section-head">
            LIVE ALERTS
            <div className="feed-controls">
              <button className={`pause-btn ${paused ? "on" : ""}`} onClick={() => setPaused((p) => !p)}>
                {paused ? "▶ Resume" : "⏸ Pause"}
              </button>
              <span className="feed-count">{alerts.length}</span>
            </div>
          </div>
          <div className="alert-list">
            {alerts.length === 0 && <div className="empty">Monitoring — no incidents</div>}
            {alerts.map((a) => {
              const sev = sevOf(a.label);
              const isSel = selected && selected._k === a._k;
              return (
                <div key={a._k} className={`alert ${isSel ? "sel" : ""}`} style={{ "--sev": sev.color }}
                     onClick={() => setSelected(isSel ? null : a)}>
                  <div className="alert-sev" style={{ background: sev.color }}>{sev.tag}</div>
                  <div className="alert-main">
                    <div className="alert-line1">
                      <span className="alert-label">{a.label}</span>
                      <span className="alert-conf">{(a.confidence * 100).toFixed(0)}%</span>
                      {a.status === "CORROBORATED" && <span className="alert-corr">×{a.detectionCount}</span>}
                    </div>
                    <div className="alert-line2">
                      <span className="alert-cam">{a.cameraName}</span>
                      <span className="alert-time">{new Date(a.timestamp).toLocaleTimeString("en-US", { hour12: false })}</span>
                    </div>
                    {isSel && (
                      <div className="alert-detail">
                        <span>Incident #{a.incidentId}</span>
                        <span>{a.status.toLowerCase()}</span>
                        <span>{a.detectionCount} detections</span>
                        <span>capture→alert {a.latencyMs}ms</span>
                      </div>
                    )}
                  </div>
                  <div className={`alert-lat ${a.latencyMs < 200 ? "ok" : "warn"}`}>{a.latencyMs}<span>ms</span></div>
                </div>
              );
            })}
          </div>
        </section>
      </main>
    </div>
  );
}

function Zone({ zone }) {
  const flagged = (zone.people || []).some((p) => p.status === "alert");
  return (
    <div className={`zone ${flagged ? "flagged" : ""}`}>
      <div className="zone-floor">
        <div className="zone-grid-lines" />
        {(zone.people || []).map((p) => (
          <span key={p.id} className={`person ${p.status === "alert" ? "alert" : ""}`}
                style={{ left: `${p.x * 100}%`, top: `${p.y * 100}%` }} />
        ))}
        <div className="zone-rec"><span className="rec-dot" />LIVE</div>
      </div>
      <div className="zone-meta">
        <span className="zone-name">{zone.name}</span>
        <span className="zone-count">{(zone.people || []).length} tracked</span>
      </div>
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
