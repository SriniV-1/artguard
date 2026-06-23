import { useEffect, useMemo, useRef, useState } from "react";

/* ArtGuard surveillance control — overhead simulation.
   6 zone displays of people as green dots (red when flagged). Click an alert to
   spotlight the subject and open its zone; click any zone to open it large;
   declare a facility-wide alert from the alert bar. Scene/alerts/facility events
   stream from the gateway over /ws/alerts as tagged envelopes. */

const FALLBACK_ZONES = [
  { id: "cam-01", name: "West Concourse" }, { id: "cam-02", name: "Main Gallery" },
  { id: "cam-03", name: "North Entrance" }, { id: "cam-04", name: "Loading Dock" },
  { id: "cam-05", name: "Central Atrium" }, { id: "cam-06", name: "Storage Wing" },
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
  const [selected, setSelected] = useState(null);   // selected alert {_k,...}
  const [expanded, setExpanded] = useState(null);    // expanded zone id
  const [facility, setFacility] = useState(null);    // facility-alert state {reason,zone,ts}
  const [resolution, setResolution] = useState(null); // resolution popup {zone,label}
  const pausedRef = useRef(false);
  const connectedRef = useRef(false);
  const resolveTimer = useRef(null);
  const seq = useRef(1);
  const clock = useClock();
  pausedRef.current = paused;

  useEffect(() => {
    let ws, sim, retry, alive = true;
    const onAlert = (a) => { if (!pausedRef.current) setAlerts((prev) => [{ ...a, _k: seq.current++ }, ...prev].slice(0, 50)); };

    const startDemo = () => {
      if (!alive || sim || connectedRef.current) return;
      setDemo(true);
      const zones = FALLBACK_ZONES.map((z) => ({ ...z, structures: [],
        people: Array.from({ length: 6 }, (_, i) => ({ id: i, x: Math.random(), y: Math.random(), tx: Math.random(), ty: Math.random(), status: "normal", until: 0 })) }));
      const bs = ["Loitering","Suspicious Movement","Erratic Movement","Intrusion"];
      sim = setInterval(() => {
        const now = Date.now();
        for (const z of zones) for (const p of z.people) {
          const dx = p.tx - p.x, dy = p.ty - p.y, d = Math.hypot(dx, dy);
          if (d < 0.02) { p.tx = Math.random(); p.ty = Math.random(); } else { p.x += dx / d * 0.008; p.y += dy / d * 0.008; }
          if (p.status === "alert" && now > p.until) p.status = "normal";
          if (p.status === "normal" && Math.random() < 0.004) { p.status = "alert"; p.until = now + 6000;
            const b = bs[Math.floor(Math.random() * bs.length)];
            onAlert({ incidentId: 1000 + seq.current, personId: p.id, cameraId: z.id, cameraName: z.name, label: b,
              confidence: 0.6 + Math.random() * 0.39, status: "OPENED", detectionCount: 1,
              latencyMs: 60 + Math.floor(Math.random() * 90), timestamp: new Date().toISOString() }); }
        }
        setScene({ cameras: zones.map((z) => ({ id: z.id, name: z.name, structures: z.structures,
          people: z.people.map((p) => ({ id: p.id, x: p.x, y: p.y, status: p.status })) })) });
      }, 100);
    };

    const connect = () => {
      try { ws = new WebSocket(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws/alerts`); }
      catch { startDemo(); return; }
      ws.onopen = () => { if (!alive) return; connectedRef.current = true; setConnected(true); setDemo(false); clearInterval(sim); sim = null; clearTimeout(kick); };
      ws.onmessage = (e) => { try { const m = JSON.parse(e.data);
        if (m.type === "scene") setScene(m.data);
        else if (m.type === "alert") onAlert(m.data);
        else if (m.type === "facility") setFacility(m.data.active ? m.data : null);
      } catch {} };
      ws.onclose = () => { if (!alive) return; connectedRef.current = false; setConnected(false); startDemo(); retry = setTimeout(connect, 4000); };
      ws.onerror = () => { try { ws.close(); } catch {} };
    };
    connect();
    const kick = setTimeout(() => { if (!connectedRef.current) startDemo(); }, 1500);
    return () => { alive = false; clearTimeout(kick); clearTimeout(retry); clearInterval(sim); if (ws) { ws.onclose = null; ws.close(); } };
  }, []); // eslint-disable-line

  const zones = scene.cameras?.length ? scene.cameras : FALLBACK_ZONES.map((z) => ({ ...z, people: [], structures: [] }));
  const stats = useMemo(() => {
    const lat = alerts.slice(0, 40).map((a) => a.latencyMs).filter((x) => x != null).sort((a, b) => a - b);
    const p95 = lat.length ? lat[Math.min(lat.length - 1, Math.floor(lat.length * 0.95))] : null;
    const open = new Set(alerts.filter((a) => a.status !== "RESOLVED").map((a) => a.incidentId)).size;
    const tracked = zones.reduce((s, c) => s + (c.people?.length || 0), 0);
    return { p95, open, total: alerts.length, tracked };
  }, [alerts, zones]);

  // clicking an alert: select it, spotlight its subject, open its zone
  const openAlert = (a) => {
    if (selected && selected._k === a._k) { setSelected(null); setExpanded(null); return; }
    setSelected(a); setExpanded(a.cameraId);
  };
  const focus = selected ? { cameraId: selected.cameraId, personId: selected.personId } : null;

  // facility-wide alert. Escalating an alert with a subject schedules an
  // automatic response: after 5s the subject is removed (escorted out) and a
  // resolution popup confirms the disturbance was dealt with.
  const POST = (url, body) => fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }).catch(() => {});
  const declareFacility = () => {
    const subj = selected ? { cameraId: selected.cameraId, personId: selected.personId, cameraName: selected.cameraName, label: selected.label } : null;
    POST("/api/facility-alert", { active: true, reason: selected ? `${selected.label} — operator escalation` : "Operator escalation", zone: subj?.cameraName || "" });
    if (subj) {
      clearTimeout(resolveTimer.current);
      resolveTimer.current = setTimeout(() => {
        POST("/api/resolve", { cameraId: subj.cameraId, personId: subj.personId }); // remove the dot (all dashboards)
        POST("/api/facility-alert", { active: false });                              // stand down
        setResolution({ zone: subj.cameraName, label: subj.label });
      }, 5000);
    }
  };
  const clearFacility = () => POST("/api/facility-alert", { active: false }).then(() => setFacility(null));
  const ackResolution = () => { setResolution(null); setExpanded(null); setSelected(null); };

  // triage: mark an alert benign (false alarm → dot back to green) or dismiss it
  const markBenign = (a) => { POST("/api/benign", { cameraId: a.cameraId, personId: a.personId }); setAlerts((p) => p.filter((x) => x._k !== a._k)); };
  const dismiss = (a) => setAlerts((p) => p.filter((x) => x._k !== a._k));

  const expandedZone = expanded ? zones.find((z) => z.id === expanded) : null;

  // ----- expanded-zone interactivity: nav between zones, per-subject controls -----
  const zoneIdx = expandedZone ? zones.findIndex((z) => z.id === expandedZone.id) : -1;
  const navZone = (dir) => {
    if (zones.length < 2 || zoneIdx < 0) return;
    setExpanded(zones[(zoneIdx + dir + zones.length) % zones.length].id);
  };
  const alertForSubject = (zid, pid) => alerts.find((a) => a.cameraId === zid && a.personId === pid);
  const selectSubject = (z, p) => {
    const a = alertForSubject(z.id, p.id);
    setSelected(a || {
      _k: `subj-${z.id}-${p.id}`, cameraId: z.id, cameraName: z.name, personId: p.id,
      label: "Flagged subject", confidence: 0.8, incidentId: null, latencyMs: null,
    });
  };
  const benignSubject = (z, p) => {
    POST("/api/benign", { cameraId: z.id, personId: p.id });
    setAlerts((prev) => prev.filter((x) => !(x.cameraId === z.id && x.personId === p.id)));
    if (selected && selected.cameraId === z.id && selected.personId === p.id) setSelected(null);
  };
  const flaggedSubjects = expandedZone ? (expandedZone.people || []).filter((p) => p.status === "alert") : [];
  const zoneAlerts = expandedZone ? alerts.filter((a) => a.cameraId === expandedZone.id).slice(0, 8) : [];

  return (
    <div className="app">
      {facility && (
        <div className="facility-banner">
          <span className="fb-pulse" />
          <span className="fb-title">FACILITY-WIDE ALERT</span>
          <span className="fb-reason">{facility.reason}{facility.zone ? ` · ${facility.zone}` : ""}</span>
          <button className="fb-clear" onClick={clearFacility}>Stand down</button>
        </div>
      )}

      <header className="topbar">
        <div className="brand">
          <span className="brand-mark" />
          <div><div className="brand-name">ARTGUARD</div><div className="brand-sub">Surveillance Control</div></div>
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
        <Stat label="Zones" value={zones.length} />
        <Stat label="People tracked" value={stats.tracked} />
        <Stat label="Open incidents" value={stats.open} accent={stats.open ? "var(--amber)" : undefined} />
        <Stat label="p95 latency" value={stats.p95 != null ? `${stats.p95} ms` : "—"}
              accent={stats.p95 != null && stats.p95 < 200 ? "var(--green)" : "var(--amber)"}
              hint={stats.p95 != null && stats.p95 < 200 ? "within 200ms SLO" : "SLO 200ms"} />
      </div>

      <main className="grid">
        <section className="cameras">
          <div className="section-head">ZONE OVERVIEW <span className="legend"><i className="lg-dot normal" />tracked<i className="lg-dot flagged" />flagged</span></div>
          <div className="zone-grid six">
            {zones.map((z) => (
              <div key={z.id} className={`zone ${(z.people || []).some((p) => p.status === "alert") ? "flagged" : ""}`}
                   onClick={() => setExpanded(z.id)} title="Open zone">
                <ZoneFloor zone={z} focus={focus} />
                <div className="zone-meta"><span className="zone-name">{z.name}</span><span className="zone-count">{(z.people || []).length} tracked</span></div>
              </div>
            ))}
          </div>
        </section>

        <section className="feed">
          <div className="section-head">
            LIVE ALERTS
            <div className="feed-controls">
              <button className="facility-btn" onClick={declareFacility} title="Escalate to a facility-wide alert">⚠ Facility Alert</button>
              <button className={`pause-btn ${paused ? "on" : ""}`} onClick={() => setPaused((p) => !p)}>{paused ? "▶" : "⏸"}</button>
              <span className="feed-count">{alerts.length}</span>
            </div>
          </div>
          <div className="alert-list">
            {alerts.length === 0 && <div className="empty">Monitoring — no incidents</div>}
            {alerts.map((a) => {
              const sev = sevOf(a.label); const isSel = selected && selected._k === a._k;
              return (
                <div key={a._k} className={`alert ${isSel ? "sel" : ""}`} style={{ "--sev": sev.color }} onClick={() => openAlert(a)}>
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
                        <span>Subject #{a.personId}</span><span>Incident #{a.incidentId}</span>
                        <span>{a.latencyMs}ms</span><span className="alert-open-hint">▣ zone opened</span>
                      </div>
                    )}
                  </div>
                  <div className={`alert-lat ${a.latencyMs < 200 ? "ok" : "warn"}`}>{a.latencyMs}<span>ms</span></div>
                  <div className="alert-actions">
                    <button className="aa benign" title="Mark benign (false alarm)" onClick={(e) => { e.stopPropagation(); markBenign(a); }}>Benign</button>
                    <button className="aa dismiss" title="Dismiss" onClick={(e) => { e.stopPropagation(); dismiss(a); }}>✕</button>
                  </div>
                </div>
              );
            })}
          </div>
        </section>
      </main>

      {expandedZone && (
        <div className="zoom-backdrop" onClick={() => { setExpanded(null); }}>
          <div className="zoom" onClick={(e) => e.stopPropagation()}>
            <div className="zoom-head">
              <button className="zoom-nav" title="Previous zone" onClick={() => navZone(-1)}>◀</button>
              <div className="zoom-id">
                <span className="zoom-name">{expandedZone.name}</span>
                <span className="zoom-sub">
                  Zone {zoneIdx + 1} of {zones.length} · {(expandedZone.people || []).length} tracked · {flaggedSubjects.length} flagged
                </span>
              </div>
              <button className="zoom-nav" title="Next zone" onClick={() => navZone(1)}>▶</button>
              <div className="zoom-head-spacer" />
              {facility
                ? <span className="zoom-escalated">● Facility alert active</span>
                : <button className="zoom-escalate" onClick={declareFacility}
                          title={selected && selected.cameraId === expandedZone.id
                            ? `Escalate ${selected.label} · subject #${selected.personId}`
                            : "Declare a facility-wide alert"}>
                    ⚠ {selected && selected.cameraId === expandedZone.id ? "Escalate subject" : "Facility alert"}
                  </button>}
              <button className="zoom-close" onClick={() => setExpanded(null)}>✕</button>
            </div>

            <div className="zoom-body">
              <div className="zoom-floor-wrap">
                <ZoneFloor zone={expandedZone} focus={focus} big onPick={(p) => selectSubject(expandedZone, p)} />
                <div className="zoom-hint">Click a subject to spotlight and act on it.</div>
              </div>

              <aside className="zoom-panel">
                <div className="zp-section">
                  <div className="zp-label">Flagged subjects <span className="zp-count">{flaggedSubjects.length}</span></div>
                  {flaggedSubjects.length === 0 && <div className="zp-empty">No flagged subjects in this zone.</div>}
                  <div className="subj-list">
                    {flaggedSubjects.map((p) => {
                      const a = alertForSubject(expandedZone.id, p.id);
                      const label = a ? a.label : "Flagged subject";
                      const sev = sevOf(label);
                      const isSel = selected && selected.cameraId === expandedZone.id && selected.personId === p.id;
                      return (
                        <div key={p.id} className={`subj ${isSel ? "sel" : ""}`} style={{ "--sev": sev.color }}
                             onClick={() => selectSubject(expandedZone, p)}>
                          <span className="subj-dot" style={{ background: sev.color }} />
                          <div className="subj-main">
                            <div className="subj-label">{label} <span className="subj-id">#{p.id}</span></div>
                            <div className="subj-meta">{a ? `${(a.confidence * 100).toFixed(0)}% · ${a.latencyMs ?? "—"}ms` : "spotlight to inspect"}</div>
                          </div>
                          <div className="subj-actions">
                            <button className="aa benign" title="Mark benign (false alarm)"
                                    onClick={(e) => { e.stopPropagation(); benignSubject(expandedZone, p); }}>Benign</button>
                            {!facility && (
                              <button className="aa escalate" title="Escalate to facility alert"
                                      onClick={(e) => { e.stopPropagation(); selectSubject(expandedZone, p); declareFacility(); }}>⚠</button>
                            )}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>

                <div className="zp-section">
                  <div className="zp-label">Zone activity <span className="zp-count">{zoneAlerts.length}</span></div>
                  {zoneAlerts.length === 0 && <div className="zp-empty">No recent alerts here.</div>}
                  <div className="zone-alert-list">
                    {zoneAlerts.map((a) => {
                      const sev = sevOf(a.label);
                      const isSel = selected && selected._k === a._k;
                      return (
                        <div key={a._k} className={`za ${isSel ? "sel" : ""}`} style={{ "--sev": sev.color }}
                             onClick={() => setSelected(a)}>
                          <span className="za-tag" style={{ background: sev.color }}>{sev.tag}</span>
                          <span className="za-label">{a.label}</span>
                          <span className="za-time">{new Date(a.timestamp).toLocaleTimeString("en-US", { hour12: false })}</span>
                        </div>
                      );
                    })}
                  </div>
                </div>
              </aside>
            </div>
          </div>
        </div>
      )}

      {resolution && (
        <div className="resolve-backdrop" onClick={ackResolution}>
          <div className="resolve" onClick={(e) => e.stopPropagation()}>
            <div className="resolve-check"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><path d="M20 6 9 17l-5-5" /></svg></div>
            <div className="resolve-title">DISTURBANCE RESOLVED</div>
            <p className="resolve-msg">
              Security was alerted to the <b>{resolution.label}</b> incident in <b>{resolution.zone}</b>.
              The subject was investigated and escorted out — the disturbance has been dealt with.
            </p>
            <button className="resolve-ok" onClick={ackResolution}>Acknowledge</button>
          </div>
        </div>
      )}
    </div>
  );
}

function ZoneFloor({ zone, focus, big, onPick }) {
  const focusId = focus && focus.cameraId === zone.id ? focus.personId : null;
  return (
    <div className={`zone-floor ${big ? "big" : ""}`}>
      <div className="zone-grid-lines" />
      {(zone.structures || []).map((s, i) => (
        <span key={i} className={`structure k-${s.kind}`}
              style={{ left: `${s.x * 100}%`, top: `${s.y * 100}%`, width: `${s.w * 100}%`, height: `${s.h * 100}%` }} />
      ))}
      {(zone.people || []).map((p) => (
        <span key={p.id} className={`person ${p.status === "alert" ? "alert" : ""} ${focusId === p.id ? "focus" : ""} ${onPick ? "pickable" : ""}`}
              style={{ left: `${p.x * 100}%`, top: `${p.y * 100}%` }}
              title={onPick ? `Subject #${p.id}` : undefined}
              onClick={onPick ? (e) => { e.stopPropagation(); onPick(p); } : undefined}>
          {focusId === p.id && <span className="focus-ring" />}
        </span>
      ))}
      <div className="zone-rec"><span className="rec-dot" />LIVE</div>
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
