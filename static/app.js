"use strict";

const paths = {
  layout: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
  activity: '<path d="M3 12h4l3-8 4 16 3-8h4"/>',
  wallet: '<path d="M20 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h15V9H5a2 2 0 0 1 0-4h15"/><path d="M20 12h-5v5h5M16 14.5h.1"/>',
  file: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6M8 13h8M8 17h5"/>',
  bell: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/>',
  settings: '<path d="m9 3-1 3-3 1-2 3 2 3v4l3 1 2 3h4l2-3 3-1v-4l2-3-2-3-3-1-1-3z"/><circle cx="12" cy="12" r="3"/>',
  send: '<path d="m22 2-7 20-4-9-9-4zM22 2 11 13"/>',
  'arrow-up-right': '<path d="M7 17 17 7M7 7h10v10"/>',
  'arrow-right': '<path d="M4 12h16m-6-6 6 6-6 6"/>',
  sparkles: '<path d="m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5zM20 2v4m-2-2h4"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  refresh: '<path d="M20 7v5h-5M4 17v-5h5"/><path d="M6 7a7 7 0 0 1 12-1l2 3M4 15l2 3a7 7 0 0 0 12-1"/>',
  pie: '<path d="M21 12a9 9 0 1 1-9-9v9z"/><path d="M16 3a8 8 0 0 1 5 5h-5z"/>',
  crosshair: '<circle cx="12" cy="12" r="7"/><path d="M12 2v5m0 10v5M2 12h5m10 0h5"/>',
  shield: '<path d="m12 2 8 4v6c0 5-8 10-8 10S4 17 4 12V6z"/><path d="m8 12 3 3 5-6"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  x: '<path d="m6 6 12 12M6 18 18 6"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7h.01"/>',
  menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
  trash: '<path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
};
const icon = (name, cls = "") => `<i class="${cls}"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name] || paths.info}</svg></i>`;
const $ = selector => document.querySelector(selector);
const $$ = selector => [...document.querySelectorAll(selector)];
const h = value => String(value ?? "").replace(/[&<>"']/g, c => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"}[c]));
const finite = value => value !== null && value !== undefined && Number.isFinite(Number(value));
const money = (value, digits = 2) => finite(value) ? Number(value).toLocaleString("en-US", {minimumFractionDigits: digits, maximumFractionDigits: digits}) : "—";
const price = value => !finite(value) ? "—" : money(value, Number(value) >= 100 ? 2 : Number(value) >= 1 ? 3 : Number(value) >= .01 ? 5 : 8);
const signed = value => finite(value) ? `${Number(value) >= 0 ? "+" : ""}${money(value)}%` : "—";
const color = value => !finite(value) ? "muted" : Number(value) >= 0 ? "positive" : "negative";
const stamp = (value, full = false) => value ? new Date(value * 1000).toLocaleString("zh-CN", {timeZone: "Asia/Shanghai", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", ...(full ? {year: "numeric"} : {})}) : "尚未更新";
const coinData = {BTC: ["₿", "Bitcoin", "btc"], ETH: ["Ξ", "Ethereum", "eth"], SOL: ["≋", "Solana", "sol"], BNB: ["◇", "BNB", "bnb"]};
const base = symbol => symbol.replace(/USDT$/, "");
function coin(symbol, extra = "") {
  const b = base(symbol), data = coinData[b] || [b.slice(0, 1), b, "other"];
  return `<span class="coin coin-${data[2]} ${extra}">${h(data[0])}</span>`;
}
function empty(title, description, name = "file") {
  return `<div class="empty-state">${icon(name)}<h3>${h(title)}</h3><p>${h(description)}</p></div>`;
}

let state = null, selected = "BTCUSDT", interval = "4h", page = "dashboard", currentReport = null;
let pinnedReport = null, chartBars = [], chartLoading = false, chartRequest = 0, settingsLoaded = false;
let showEMA20 = true, showEMA50 = true, currentDialog = null, pollBusy = false;
const sparkCache = new Map(), candleRequests = new Set(), knownJobs = new Map();
let lastMode = null, analysisModeLoaded = false, newsData = null, newsRequest = 0;
const MARKET = "binance_usdm";

async function api(path, data) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), path.includes("telegram") ? 28000 : 25000);
  try {
    const response = await fetch(path, {signal: controller.signal, cache: "no-store", ...(data !== undefined ? {
      method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(data),
    } : {})});
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || "操作未完成");
    return result;
  } catch (error) {
    if (error.name === "AbortError") throw new Error("请求超时，请检查网络或稍后重试");
    throw error;
  } finally { clearTimeout(timer); }
}

function toast(message, error = false) {
  const el = document.createElement("div");
  el.className = `toast ${error ? "error" : ""}`;
  el.innerHTML = `${icon(error ? "info" : "check")}<span>${h(message)}</span><button aria-label="关闭提示">×</button>`;
  el.querySelector("button").onclick = () => el.remove();
  $("#toast-region").appendChild(el);
  setTimeout(() => el.remove(), error ? 12000 : 6500);
}

async function refreshState(silent = false) {
  if (pollBusy) return;
  pollBusy = true;
  try {
    state = await api("/api/state");
    if (lastMode !== null && lastMode !== state.demo) {
      sparkCache.clear(); chartBars = []; pinnedReport = null;
      await loadChart(); await loadNews();
    }
    lastMode = state.demo;
    processJobs();
    render();
    if (!settingsLoaded) populateSettings();
  } catch (error) {
    $("#connection-status").innerHTML = '<b class="status-dot error"></b> 服务未连接';
    if (!silent) toast(error.message, true);
  } finally { pollBusy = false; }
}

function processJobs() {
  for (const job of state.jobs) {
    const previous = knownJobs.get(job.id);
    if (previous === "running" && job.status !== "running") {
      if (job.status === "done") {
        pinnedReport = null;
        toast(job.push_error ? `报告已生成，${job.push_error}` : job.pushed ? "分析报告已生成，并已推送 Telegram" : "分析报告已生成并保存");
        if (job.errors?.length) toast(`部分币种分析未完成：${job.errors.join("；")}`, true);
      } else toast(job.error || "分析未完成", true);
    }
    knownJobs.set(job.id, job.status);
  }
}

function render() {
  if (!state) return;
  const q = state.quotes[selected];
  const stale = state.last_poll && Date.now() / 1000 - state.last_poll > Math.max(45, state.settings.poll_seconds * 2);
  const connected = !state.market_error && state.last_poll && !stale;
  $("#connection-status").innerHTML = `<b class="status-dot ${connected ? state.demo ? "warning" : "" : "error"}"></b>${state.demo ? "演示数据" : connected ? `${state.settings.poll_seconds} 秒更新` : state.last_poll ? "行情连接异常" : "正在连接行情"}`;
  $("#watch-count").textContent = state.watchlist.length;
  $("#telegram-side-label").innerHTML = `${state.settings.telegram_configured ? "已配置 · 查看推送设置" : "连接 Telegram"} ${icon("arrow-up-right")}`;
  $$(".poll-interval").forEach(el => el.textContent = state.settings.poll_seconds);
  const banner = $("#mode-banner");
  banner.hidden = !state.demo && !state.market_error && !stale;
  banner.className = `mode-banner ${!state.demo ? "data-error" : ""}`;
  banner.innerHTML = state.demo ? `${icon("info")}<span>演示模式 · 当前价格、图表与计划均为合成数据，用于体验功能。</span><button data-action="live-mode">切换真实行情</button>` : `${icon("info")}<span>${h(state.market_error || "行情暂未更新，请检查服务和网络。")}</span><button data-page="settings">查看设置</button>`;
  const options = [...new Set([...state.watchlist, ...state.positions.map(p => p.symbol), selected])];
  const selector = $("#chart-symbol");
  if ([...selector.options].map(o => o.value).join() !== options.join()) selector.innerHTML = options.map(s => `<option value="${h(s)}">${h(base(s))} / USDT</option>`).join("");
  selector.value = selected;
  $("#chart-coin").outerHTML = coin(selected).replace('class="coin ', 'id="chart-coin" class="coin ');
  $("#chart-price").textContent = q ? price(q.price) : "—";
  $("#chart-change").textContent = q ? signed(q.change_pct) : "—";
  $("#chart-change").className = `change ${color(q?.change_pct)}`;
  $("#chart-asof").textContent = q ? `${stamp(q.timestamp)} 北京时间` : "等待更新";
  currentReport = pinnedReport?.symbol === selected ? pinnedReport : state.reports.find(r => r.symbol === selected && r.demo === state.demo && r.market === MARKET) || null;
  if (!analysisModeLoaded) { $("#analysis-mode").value = state.settings.analysis_mode; analysisModeLoaded = true; }
  $("#analysis-mode option[value=ai]").disabled = !state.settings.ai_configured;
  if (!state.settings.ai_configured && $("#analysis-mode").value === "ai") $("#analysis-mode").value = "rules";
  $("#ai-readiness").innerHTML = state.settings.ai_configured
    ? "模型已配置（未验证连接） · AI 会使用本次取得的合约快照与新闻摘要。"
    : '未配置 AI：当前仅能生成技术规则报告。<button class="text-button" data-page="settings">连接 AI 模型 →</button>';
  renderDerivatives();
  renderTickers(); renderAnalysis(); renderAllocation(); renderObservation();
  if (page === "watchlist") renderWatchlist();
  if (page === "positions") renderPositions();
  if (page === "history") renderHistory();
  if (page === "alerts") renderAlerts();
  updateSettingsStatus();
  const running = state.jobs.find(j => j.status === "running");
  [$("#analyze-button"), $("#briefing-button"), $("#history-briefing")].forEach(b => b.disabled = Boolean(running));
  $("#analyze-button").innerHTML = `${icon("sparkles")}<span>${running ? h(running.progress) : `生成 ${h(base(selected))} ${$("#analysis-mode").value === "ai" ? "AI 分析" : "规则分析"}`}</span>${icon("arrow-right")}`;
  $("#briefing-button span").textContent = $("#analysis-mode").value === "ai" ? "生成 AI 自选简报" : "生成规则自选简报";
  if (running && !currentReport) $("#analysis-content").innerHTML = `<div class="analysis-empty"><div class="analysis-empty-icon">${icon("sparkles")}</div><strong>正在整理市场的线索</strong><p>${h(running.progress)}</p><div class="running-indicator"></div><p class="tiny">正在整理合约快照；AI 模式还会调用模型，请保留服务运行。</p></div>`;
}

function renderTickers() {
  const list = state.watchlist.slice(0, 4);
  $("#ticker-grid").innerHTML = list.length ? list.map(symbol => {
    const q = state.quotes[symbol];
    const old = q && Date.now() / 1000 - q.timestamp > 90;
    const points = sparkCache.get(symbol);
    return `<button class="ticker-card ${symbol === selected ? "selected" : ""} ${old ? "stale" : ""}" data-action="select-coin" data-symbol="${h(symbol)}"><span class="ticker-title">${coin(symbol)}${h(base(symbol))}<span class="quote-name">/ USDT</span><span class="ticker-trail">${old ? "已过期" : state.demo ? "DEMO" : "PERP"}</span></span><span class="ticker-price">${q ? price(q.price) : "—"}</span><span class="ticker-bottom"><span><span class="change ${color(q?.change_pct)}">${signed(q?.change_pct)}</span><span class="ticker-period">24h</span></span>${points ? sparkline(points, q?.change_pct >= 0) : '<span class="sparkline-placeholder">读取走势中</span>'}</span></button>`;
  }).join("") : '<button class="ticker-card" data-action="add-watch"><span class="ticker-title">＋ 添加第一个关注币种</span><span class="muted small">从 BTC、ETH 或你关注的币种开始</span></button>';
  for (const symbol of list) loadSparkline(symbol);
}

function sparkline(values, positive) {
  const min = Math.min(...values), max = Math.max(...values), range = max - min || 1;
  const d = values.map((v, i) => `${i ? "L" : "M"}${(i / (values.length - 1) * 78).toFixed(1)},${(25 - (v - min) / range * 22).toFixed(1)}`).join(" ");
  return `<svg class="sparkline" viewBox="0 0 80 28" aria-hidden="true"><path d="${d} L78 28 L0 28 Z" fill="${positive ? "#9fce7610" : "#e58d8b10"}"/><path d="${d}" stroke="${positive ? "#9fce76" : "#c98d7f"}" fill="none" stroke-width="1.5"/></svg>`;
}

async function loadSparkline(symbol) {
  if (sparkCache.has(symbol) || candleRequests.has(symbol)) return;
  const mode = state.demo;
  candleRequests.add(symbol);
  try {
    const result = await api(`/api/candles?symbol=${encodeURIComponent(symbol)}&interval=1h`);
    if (state?.demo === mode && result.demo === mode) {
      sparkCache.set(symbol, result.bars.slice(-30).map(b => b.close));
      renderTickers();
    }
  } catch (_) { /* Quote errors are shown by the monitor; no fabricated sparkline. */ }
  finally { candleRequests.delete(symbol); }
}

function reportInvalidReason(report) {
  if (!report) return "尚未生成报告";
  if (report.market !== MARKET) return "旧现货报告，仅供历史回看";
  if (report.demo !== state.demo) return "行情模式已变化，请重新分析";
  if (report.stale || !report.valid_until || Date.now() / 1000 > report.valid_until) return "报告已过期，请重新分析";
  if (report.plan_context !== state.plan_context) return "风险参数或持仓已变化，请重新分析";
  const quote = state.quotes[report.symbol];
  if (state.market_error || !quote || Date.now() / 1000 - Math.min(quote.timestamp, quote.mark_timestamp || 0) > 90) return "当前合约报价异常，暂停显示仓位建议";
  return "";
}

function engineLabel(report) {
  return report.engine === "ai" ? report.ai_scope === "market_and_events" ? "AI 事件解读 + 规则测算" : "AI 技术解读 · 热点未纳入" : "技术规则 · 未调用 AI";
}

function renderDerivatives() {
  const q = state.quotes[selected], d = currentReport?.derivatives;
  const freshOI = d?.timestamp && Date.now() / 1000 - d.timestamp <= 90;
  $("#derivatives-strip").innerHTML = q ? `<span>标记价<strong>${price(q.mark_price)}</strong></span><span>指数价<strong>${price(q.index_price)}</strong></span><span>本期资金费率<strong>${(q.funding_rate * 100).toFixed(4)}%</strong></span><span>下次结算<strong>${stamp(q.next_funding_time)}</strong></span><span>持仓量 · ${h(base(selected))}<strong>${freshOI ? money(d.open_interest) : d?.error ? "暂不可用" : d ? "快照已过期" : "生成分析后查看"}</strong></span><small>标记价截至 ${stamp(q.mark_timestamp)}${freshOI ? ` · 持仓量截至 ${stamp(d.timestamp)}` : ""}。K 线与预警使用成交价；正费率通常多付空收。</small>` : '<p>合约报价暂不可用，请检查服务器到币安合约接口的连接。</p>';
}

function citedNewsMarkup(report) {
  const refs = new Set(report.ai?.event_refs || []);
  return (report.news?.items || []).filter(item => refs.has(item.id)).map(item => `<p class="report-source">[${h(item.id)}] ${h(item.source)} · ${stamp(item.published)}<br><a href="${h(item.url)}" target="_blank" rel="noopener noreferrer">${h(item.title)} ↗</a></p>`).join("");
}

function renderAnalysis() {
  const report = currentReport;
  $("#engine-badge").textContent = report ? engineLabel(report) : "尚未生成报告";
  $("#engine-badge").className = `badge ${report?.engine === "ai" ? "ai" : ""}`;
  if (!report) {
    $("#analysis-content").innerHTML = `<div class="analysis-empty"><strong>先选择分析方式，再生成报告</strong><p>规则分析计算多周期趋势、成交量和风险预算；AI 分析另行解读合约数据与已取得的新闻摘要。</p><span class="tiny">${state.settings.ai_configured ? "模型已配置，可选择 AI 综合分析" : "未配置模型，规则分析可独立运行"}</span></div>`;
    $("#analysis-footnote").textContent = "成交价 K 线参与技术分析；报告为快照，有效期 5 分钟。";
    return;
  }
  const invalid = reportInvalidReason(report);
  $("#analysis-content").innerHTML = `${invalid ? `<div class="analysis-warning report-expired">${h(invalid)}；下方仅为历史判断。</div>` : ""}<div class="analysis-verdict"><span class="verdict ${h(report.signal)}">${h(report.signal_label)}</span><span class="analysis-time">${h(stamp(report.created))}</span></div><p class="analysis-summary">${h(report.ai?.summary || report.summary)}</p><div class="evidence-columns"><div><div class="evidence-label">看多依据</div><ul class="evidence-list">${(report.ai ? [report.ai.bull_case] : report.bullish.slice(0, 2)).map(t => `<li>${h(t)}</li>`).join("")}</ul></div><div><div class="evidence-label">看空依据与风险</div><ul class="evidence-list bear">${(report.ai ? [report.ai.bear_case] : report.bearish.slice(0, 2)).map(t => `<li>${h(t)}</li>`).join("")}</ul></div></div>${report.ai ? `<div class="evidence-label">事件影响 · ${h((report.ai.event_refs || []).join(" / ") || "无引用")}</div><p class="analysis-summary">${h(report.ai.event_assessment)}</p>${citedNewsMarkup(report)}` : '<p class="field-help">技术规则信号不包含新闻判断，也未调用 AI。</p>'}${report.warnings.length ? `<div class="analysis-warning">${h(report.warnings.join("；"))}</div>` : ""}`;
  $("#analysis-footnote").textContent = `行情截至 ${stamp(report.data_asof)} · ${report.demo ? "演示" : report.source} · ${invalid || "5 分钟内的条件快照，非立即开仓指令"}`;
}

function renderAllocation() {
  const report = currentReport, invalid = reportInvalidReason(report);
  if (invalid) {
    $("#allocation-content").innerHTML = `<div class="empty-compact">${icon("pie")}<div><h3>${h(invalid)}</h3><p>重新生成合约分析后展示名义仓位、估算初始保证金与止损风险。旧快照金额已隐藏。</p><button class="text-button" data-page="settings">账户权益假设 ${money(state.settings.capital)} USDT →</button></div></div>`;
    return;
  }
  const a = report.allocation, l = report.levels;
  const invested = Math.min(100, a.occupied / a.capital * 100), proposed = Math.min(100 - invested, a.margin_amount / a.capital * 100);
  const reserved = Math.min(100 - invested - proposed, a.reserve / a.capital * 100);
  $("#allocation-content").innerHTML = `<div class="allocation-body"><div class="allocation-label">${a.amount > 0 ? "条件确认后 · 名义仓位上限" : "当前暂不开新仓"} ${report.demo ? "（演示）" : ""}</div><div class="allocation-amount">${money(a.amount)}<span>USDT</span></div><div class="allocation-detail"><span>估算初始保证金<b>${money(a.margin_amount)} USDT</b></span><span>计划方向 / 杠杆<b>${report.direction === "short" ? "偏空" : report.direction === "long" ? "偏多" : "观望"} / ${a.leverage}x</b></span></div><div class="allocation-bar" aria-label="保证金分配"><span class="occupied" style="width:${invested}%"></span><span class="allocated" style="width:${proposed}%"></span><span class="reserved" style="width:${reserved}%"></span></div><div class="allocation-detail"><span>风险预算<b>${money(a.risk_budget)}</b></span><span>止损损失含往返成本<b>${money(a.planned_loss)}</b></span><span>下次资金费估算（正数为支出）<b>${money(a.next_funding_estimate)}</b></span></div>${report.direction !== "none" ? `<div class="plan-levels"><div><span>观察入场</span><strong>${price(l.entry)}</strong></div><div><span>计划止损</span><strong>${price(l.stop)}</strong></div><div><span>观察目标 · 2R</span><strong>${price(l.target)}</strong></div></div>` : `<p class="field-help">当前没有有效多空开仓计划，不显示入场/止损/目标。</p>`}<p class="allocation-note">${h(a.formula)}。已有保证金估算 ${money(a.occupied)}，预留 ${money(a.reserve)} USDT。资金费按当前费率、名义仓位估算单次结算，未计入止损预算。未计算强平价；80% 保证金比例检查不是强平保障。当前条件尚未确认，不表示立即开仓。</p></div>`;
}

function renderObservation() {
  const r = currentReport, invalid = reportInvalidReason(r);
  $("#observation-content").innerHTML = invalid ? `<div class="empty-compact"><div><h3>${h(invalid)}</h3><p>重新分析后查看最新触发条件与失效条件。</p></div></div>` : `<div class="observation-body"><div class="condition"><div><h3>技术规则 · 等待确认</h3><p>${h(r.trigger)}</p></div></div><div class="condition stop"><div><h3>技术规则 · 判断失效</h3><p>${h(r.invalidation)}</p></div></div>${r.ai ? `<div class="condition"><div><h3>AI 补充观察</h3><p>${h(r.ai.watch_next)}</p></div></div>` : ""}<div class="unknown-line">${h(r.unknowns.join("；"))}</div></div>`;
}

function newsMarkup(news) {
  if (!news) return '<p>正在读取新闻…</p>';
  const labels = {ok: "已取得近期新闻", partial: "部分新闻源不可用", empty: "近 24 小时未匹配到相关新闻", unavailable: "新闻源不可用 · 未结合热点", demo: "演示模式 · 未获取真实新闻"};
  return `<p class="news-status">${h(labels[news.status] || news.status)}${news.fetched_at ? ` · 获取于 ${stamp(news.fetched_at)}` : ""}</p><p class="field-help">${h(news.note || "")}</p>${(news.errors || []).map(e => `<p class="analysis-warning">${h(e)}</p>`).join("")}<div class="news-list">${(news.items || []).map(item => `<article class="news-item"><div class="news-meta">[${h(item.id)}] ${h(item.source)} · ${stamp(item.published, true)} · ${h(item.scope)}</div><a href="${h(item.url)}" target="_blank" rel="noopener noreferrer">${h(item.title)} ↗</a><p>${h(item.excerpt)}</p></article>`).join("")}</div>`;
}

async function loadNews() {
  const request = ++newsRequest, symbol = selected;
  newsData = null;
  $("#news-content").innerHTML = newsMarkup(null);
  try {
    const news = await api("/api/news?symbol=" + encodeURIComponent(symbol));
    if (request !== newsRequest) return;
    newsData = news;
    $("#news-content").innerHTML = newsMarkup(news);
  } catch (error) {
    if (request === newsRequest) $("#news-content").innerHTML = `<p class="analysis-warning">新闻未取得：${h(error.message)}。不会以模型记忆补写热点。</p>`;
  }
}

function renderWatchlist() {
  const rows = state.watchlist.map(symbol => {
    const q = state.quotes[symbol], name = base(symbol), report = state.reports.find(r => r.symbol === symbol && r.demo === state.demo && r.market === MARKET);
    const stale = q && Date.now() / 1000 - q.timestamp > 90;
    return `<tr><td><div class="table-asset">${coin(symbol)}<span>${h(name)}<small>${h(coinData[name]?.[1] || name)} / USDT</small></span></div></td><td class="mono">${price(q?.price)}</td><td class="mono ${color(q?.change_pct)}">${signed(q?.change_pct)}</td><td>${stale ? '<span class="negative">数据已过期</span>' : q ? `<span class="alert-status"><b class="status-dot ${state.demo ? "warning" : ""}"></b>${state.demo ? "演示" : "跟踪中"}</span>` : '<span class="muted">暂无报价</span>'}</td><td><span class="badge">${h(report?.signal_label || "等待分析")}</span></td><td><div class="row-actions"><button class="text-button" data-action="select-coin" data-symbol="${h(symbol)}">分析 ${icon("arrow-up-right")}</button><button class="text-button" data-action="add-alert" data-symbol="${h(symbol)}">设预警</button><button class="icon-button danger-button" data-action="delete-watch" data-symbol="${h(symbol)}" aria-label="移除 ${h(name)}">${icon("x")}</button></div></td></tr>`;
  });
  $("#watchlist-table").innerHTML = rows.length ? `<table><thead><tr><th>币种</th><th>最新价格 · USDT</th><th>24h 涨跌</th><th>数据状态</th><th>最近判断</th><th>操作</th></tr></thead><tbody>${rows.join("")}</tbody></table>` : empty("还没有关注币种", "添加你希望持续跟踪的 币安 USDT 永续合约。", "activity");
}

function renderPositions() {
  const positions = state.positions.filter(p => p.market === MARKET);
  const legacy = state.positions.filter(p => p.market !== MARKET);
  const margin = positions.reduce((a, p) => a + p.margin, 0);
  const pnl = positions.reduce((a, p) => a + (p.pnl || 0), 0);
  const missing = positions.some(p => p.pnl === null);
  $("#position-overview").innerHTML = `<div class="metric-card"><p>估算初始保证金</p><strong>${money(margin)}</strong><small>开仓名义金额 ÷ 记录杠杆</small></div><div class="metric-card"><p>标记价浮盈亏${missing ? "（部分缺报价）" : ""}</p><strong class="${color(pnl)}">${money(pnl)}</strong><small>${state.demo ? "演示标记价 · 非真实盈亏" : "未计手续费与资金费"}</small></div><div class="metric-card"><p>权益假设占用</p><strong>${money(margin / state.settings.capital * 100, 1)}%</strong><small>非交易所实际可用余额</small></div>`;
  $("#positions-table").innerHTML = state.positions.length ? `<table><thead><tr><th>币种 / 市场</th><th>方向 / 杠杆</th><th>数量 / 开仓价</th><th>标记价</th><th>浮盈亏 / 保证金收益率</th><th>操作</th></tr></thead><tbody>${[...positions, ...legacy].map(p => `<tr><td>${h(base(p.symbol))}<small class="position-market">${p.market === MARKET ? "USDT 永续" : "旧现货 · 已排除"}</small></td><td>${p.market === MARKET ? `${p.side === "short" ? "空" : "多"} / ${p.leverage}x` : "—"}</td><td>${h(p.quantity)} / ${price(p.entry)}</td><td>${price(p.mark)}</td><td class="${color(p.pnl)}">${p.pnl === null ? "不估值 / 行情缺失" : `${money(p.pnl)} USDT (${signed(p.pnl_pct)})`}</td><td><button class="text-button danger-button" data-action="delete-position" data-id="${p.id}">删除记录</button></td></tr>`).join("")}</tbody></table>` : empty("录入一笔合约净持仓", "填写币种、方向、基础币数量、开仓均价和杠杆；同币种再次保存会更新合约净持仓。", "wallet");
}

function renderHistory() {
  $("#history-list").innerHTML = state.reports.length ? state.reports.map(r => `<article class="report-card">${coin(r.symbol)}<div class="report-text"><h3>${h(base(r.symbol))} / USDT <span class="badge ${r.demo ? "demo" : r.engine === "ai" ? "ai" : ""}">${r.market !== MARKET ? "旧现货报告" : engineLabel(r)}${r.demo ? " · 演示" : ""}</span><span class="badge">${h(r.signal_label)}</span></h3><p>${h(r.ai?.summary || r.summary)}</p><div class="report-meta"><span>${stamp(r.created, true)}</span><span>${h(r.primary_tf)} 周期</span><span>参考价 ${price(r.price)}</span><span>${r.market === MARKET ? "快照名义仓位" : "旧现货金额"} ${money(r.allocation.amount)} USDT</span></div></div><div class="row-actions"><button class="text-button" data-action="open-report" data-id="${r.id}">查看报告 ${icon("arrow-up-right")}</button><button class="text-button" data-action="push-report" data-id="${r.id}">${icon("send")} 推送</button></div></article>`).join("") : `<div class="panel">${empty("每次判断，值得留下", "生成第一份行情分析后，你可以在这里回看当时的数据、触发条件与资金计划。")}</div>`;
}

function renderAlerts() {
  $("#alerts-table").innerHTML = state.alerts.length ? `<table><thead><tr><th>币种</th><th>触发条件</th><th>当前价格</th><th>状态</th><th>创建时间</th><th></th></tr></thead><tbody>${state.alerts.map(a => `<tr><td><div class="table-asset">${coin(a.symbol)}${h(base(a.symbol))}</div></td><td class="mono">${a.direction === "above" ? "≥" : "≤"} ${price(a.price)} USDT</td><td class="mono">${a.market === MARKET ? price(state.quotes[a.symbol]?.price) : "—"}</td><td><span class="alert-status ${a.active ? "" : "fired"}"><b class="status-dot ${a.active ? "" : "warning"}"></b>${a.market !== MARKET ? "旧现货 · 已停用" : a.active ? "等待到价" : `已触发 · ${stamp(a.fired)}`}</span></td><td class="muted small">${stamp(a.created)}</td><td><button class="text-button danger-button" data-action="delete-alert" data-id="${a.id}">删除</button></td></tr>`).join("")}</tbody></table>` : empty("只关注对你重要的价位", "设置向上突破或向下跌破提醒。每条预警触发一次，避免重复打扰。", "bell");
  $("#notification-list").innerHTML = state.notifications.length ? state.notifications.map(n => `<article class="notification">${icon(n.kind === "alert" ? "bell" : n.kind === "error" ? "info" : "file")}<div><h3>${h(n.title)} ${n.delivered ? '<span class="badge success">已推送</span>' : ""}</h3><p>${h(n.message)}</p></div><time>${stamp(n.created)}</time></article>`).join("") : '<p class="muted small">尚无消息。预警触发与报告生成结果将记录在这里。</p>';
}

function updateSettingsStatus() {
  $("#llm-setting-status").textContent = state.settings.ai_configured ? "已配置" : "未配置";
  $("#llm-setting-status").className = `badge ${state.settings.ai_configured ? "ai" : ""}`;
  $("#tg-setting-status").textContent = state.settings.telegram_configured ? "已配置" : "未配置";
  $("#tg-setting-status").className = `badge ${state.settings.telegram_configured ? "success" : ""}`;
  $("#data-mode-title").textContent = state.demo ? "演示数据 · 非真实行情" : "真实公共行情";
  $("#mode-toggle").textContent = state.demo ? "切换真实行情" : "体验演示模式";
}

function populateSettings() {
  if (!state) return;
  const form = $("#settings-form");
  for (const [key, value] of Object.entries(state.settings)) {
    const input = form.elements.namedItem(key);
    if (!input) continue;
    if (input.type === "checkbox") input.checked = Boolean(value); else input.value = value;
  }
  form.elements.llm_api_key.value = "";
  form.elements.telegram_bot_token.value = "";
  form.elements.llm_api_key.placeholder = state.settings.has_llm_key ? "已保存密钥 · 留空保留" : "未配置";
  form.elements.telegram_bot_token.placeholder = state.settings.has_telegram_token ? "已保存 Token · 留空保留" : "从 @BotFather 获取";
  settingsLoaded = true;
}

async function saveSettings(quiet = false) {
  const form = $("#settings-form");
  if (!form.reportValidity()) return false;
  const payload = Object.fromEntries(new FormData(form));
  for (const key of ["telegram_enabled", "daily_report_enabled"]) payload[key] = form.elements[key].checked;
  await api("/api/settings", payload);
  settingsLoaded = false; analysisModeLoaded = false;
  await refreshState();
  if (!quiet) toast("设置已保存，新分析将使用更新后的资金与观察周期");
  return true;
}

function showPage(name) {
  if (!$("#page-" + name)) return;
  page = name;
  $$(".page").forEach(el => el.classList.toggle("active", el.id === "page-" + name));
  $$(".nav-item").forEach(el => el.classList.toggle("active", el.dataset.page === name));
  $("#breadcrumb-title").textContent = {dashboard: "市场看板", watchlist: "自选盯盘", positions: "我的持仓", history: "分析报告", alerts: "价格预警", settings: "偏好与连接"}[name];
  $(".sidebar").classList.remove("open");
  if (name === "settings") populateSettings();
  render();
  if (name === "dashboard") requestAnimationFrame(drawChart);
  window.scrollTo({top: 0, behavior: "instant"});
}

function selectCoin(symbol) {
  selected = symbol; pinnedReport = null;
  showPage("dashboard");
  loadChart(); loadNews();
}

async function analyze(briefing = false) {
  try {
    const mode = $("#analysis-mode").value;
    const result = await api(briefing ? "/api/briefing" : "/api/analyze", {mode, ...(briefing ? {} : {symbol: selected})});
    knownJobs.set(result.job_id, "running");
    toast(briefing ? "已开始生成自选简报，可继续查看其他页面" : `已开始分析 ${base(selected)}`);
    await refreshState();
  } catch (error) { toast(error.message, true); }
}

function openDialog(kind, symbol = selected) {
  currentDialog = kind;
  const dialog = $("#form-dialog"); dialog.classList.remove("report-dialog");
  const options = state.watchlist.map(s => `<option value="${h(base(s))}"></option>`).join("");
  const symbolField = `<label>币种<input name="symbol" value="${kind === "watch" ? "" : h(base(symbol))}" placeholder="例如 BTC、ETH、SOL" list="symbol-list" required maxlength="19" autocomplete="off"></label><datalist id="symbol-list">${options}</datalist>`;
  $("#dialog-title").textContent = {watch: "添加自选币种", position: "录入 USDT 永续净持仓", alert: "新建价格预警"}[kind];
  $("#dialog-fields").innerHTML = symbolField + (kind === "position" ? '<label>方向<select name="side"><option value="long">做多 · Long</option><option value="short">做空 · Short</option></select></label><label>记录杠杆<input name="leverage" type="number" min="1" max="20" step="1" value="3" required></label><label>基础币数量（不是保证金）<input name="quantity" type="number" min="0.000000000001" step="any" placeholder="例如 0.05" required></label><label>开仓均价 · USDT<input name="entry" type="number" min="0.000000000001" step="any" placeholder="例如 85000" required></label><p class="dialog-help">同币种再次保存会更新合约净持仓；旧现货记录保留。暂不支持同币种双向持仓或精确强平价。</p>' : kind === "alert" ? `<label>触发方向<select name="direction"><option value="above">向上到达 · 大于或等于</option><option value="below">向下到达 · 小于或等于</option></select></label><label>目标价格 · USDT<input name="price" type="number" min="0.000000000001" step="any" value="${state.quotes[symbol] ? Number((state.quotes[symbol].price * 1.02).toPrecision(7)) : ""}" required></label><p class="dialog-help">每条预警只触发一次。首次读取价格已满足条件时也会触发；演示模式暂停触发。</p>` : '<p class="dialog-help">支持币安正在交易的 USDT 永续合约，最多关注 20 个币种。</p>');
  if (kind === "position") $("#dialog-form").elements.leverage.value = state.settings.leverage;
  $("#dialog-submit").hidden = false; $("#dialog-submit").textContent = kind === "position" ? "保存持仓" : "确认添加";
  $("#dialog-cancel").textContent = "取消";
  dialog.showModal();
}

async function submitDialog(event) {
  event.preventDefault();
  if (!currentDialog) return;
  const payload = Object.fromEntries(new FormData(event.target));
  const endpoint = {watch: "/api/watchlist", position: "/api/positions", alert: "/api/alerts"}[currentDialog];
  $("#dialog-submit").disabled = true;
  try {
    await api(endpoint, payload);
    $("#form-dialog").close();
    toast({watch: "已添加自选，正在读取行情", position: "持仓记录已保存", alert: "价格预警已创建"}[currentDialog]);
    await refreshState();
  } catch (error) { toast(error.message, true); }
  finally { $("#dialog-submit").disabled = false; }
}

async function openReport(ident) {
  const r = await api("/api/reports/" + ident);
  const a = r.allocation, l = r.levels;
  const invalid = reportInvalidReason(r);
  const dialog = $("#form-dialog"); dialog.classList.add("report-dialog"); currentDialog = null;
  $("#dialog-title").textContent = `${base(r.symbol)} ${r.market === MARKET ? "USDT 永续" : "旧现货"}报告${r.demo ? " · 演示" : ""}`;
  $("#dialog-fields").innerHTML = `<div class="report-detail">${invalid ? `<div class="analysis-warning">${h(invalid)}；历史金额仅为当时快照。</div>` : ""}<p class="field-help">${h(engineLabel(r))}</p><div class="analysis-verdict"><span class="verdict ${h(r.signal)}">${h(r.signal_label)}</span><span class="muted small">${stamp(r.created, true)}</span></div><p class="analysis-summary">${h(r.ai?.summary || r.summary)}</p><div class="evidence-label">行情与来源</div><p class="small">${h(r.source)} · 行情截至 ${stamp(r.data_asof, true)}<br>参考价 ${price(r.price)} USDT · ${h(r.primary_tf)} 周期</p><div class="evidence-label">支持判断的依据</div><ul class="evidence-list">${r.bullish.map(t => `<li>${h(t)}</li>`).join("")}</ul><div class="evidence-label">反向证据</div><ul class="evidence-list bear">${r.bearish.map(t => `<li>${h(t)}</li>`).join("")}</ul><div class="plan-levels"><div><span>观察入场</span><strong>${price(l.entry)}</strong></div><div><span>计划失效</span><strong>${price(l.stop)}</strong></div><div><span>观察目标</span><strong>${price(l.target)}</strong></div></div><div class="evidence-label">投入测算</div><p class="small">${r.market === MARKET ? "当时名义仓位" : "旧现货金额"} ${money(a.amount)} USDT；${r.market === MARKET ? `估算初始保证金 ${money(a.margin_amount)} USDT，杠杆 ${a.leverage}x；` : ""}风险预算 ${money(a.risk_budget)} USDT；计划损失含预估成本 ${money(a.planned_loss)} USDT。<br>总资金假设 ${money(a.capital)} USDT，已有${r.market === MARKET ? "保证金估算" : "持仓估值"} ${money(a.occupied)}，现金预留 ${money(a.reserve)}。</p><p class="allocation-note">${h(a.formula)}</p><div class="evidence-label">触发与失效</div><p class="small">${h(r.trigger)}<br>${h(r.invalidation)}</p>${r.position ? `<div class="evidence-label">你的持仓</div><p class="small">数量 ${h(r.position.quantity)} · 成本 ${price(r.position.entry)} · 浮动盈亏 ${money(r.position.pnl)} USDT<br>${h(r.position.note)}</p>` : ""}${r.warnings.length ? `<div class="analysis-warning">${h(r.warnings.join("；"))}</div>` : ""}${r.news ? `<div class="evidence-label">报告生成时的新闻快照</div>${r.ai ? `<p>${h(r.ai.event_assessment || "")}</p><p>引用：${h((r.ai.event_refs || []).join(" / ") || "无")}</p>` : ""}${newsMarkup(r.news)}` : ""}<p class="allocation-note">${h(r.unknowns.join("；"))}。${h(r.disclaimer)}</p></div>`;
  $("#dialog-submit").hidden = true; $("#dialog-cancel").textContent = "关闭报告";
  dialog.showModal();
}

async function toggleMode(demo) {
  await api("/api/mode", {demo});
  sparkCache.clear(); chartBars = []; pinnedReport = null;
  toast(demo ? "已切换演示模式，演示不会触发真实价格预警" : "已切换真实行情，正在连接公共数据源");
  await refreshState();
  await loadChart(); await loadNews();
}

async function loadChart() {
  const id = ++chartRequest, symbol = selected, tf = interval;
  chartLoading = true;
  $("#chart-message").hidden = false;
  $("#chart-message").textContent = "正在读取已收盘 K 线…";
  try {
    const result = await api(`/api/candles?symbol=${encodeURIComponent(symbol)}&interval=${tf}`);
    if (id !== chartRequest) return;
    chartBars = result.bars;
    $("#chart-source").textContent = result.source;
    $("#chart-count").textContent = `${result.bars.length} 根已收盘 K 线${result.demo ? " · 演示" : ""}`;
    $("#chart-message").hidden = true;
    drawChart();
  } catch (error) {
    if (id !== chartRequest) return;
    chartBars = [];
    $("#chart-message").textContent = error.message;
    $("#chart-count").textContent = "暂无可用 K 线";
    drawChart();
  } finally { if (id === chartRequest) chartLoading = false; }
}

function emaSeries(values, period) {
  const alpha = 2 / (period + 1); let average = values[0];
  return values.map(value => (average += alpha * (value - average)));
}

let chartGeometry = null;
function drawChart(hover = -1) {
  const canvas = $("#market-chart"), container = $("#chart-container");
  const width = container.clientWidth, height = container.clientHeight;
  if (!width || !height) return;
  const dpr = window.devicePixelRatio || 1;
  canvas.width = width * dpr; canvas.height = height * dpr;
  const ctx = canvas.getContext("2d"); ctx.scale(dpr, dpr);
  ctx.fillStyle = "#171f2b"; ctx.fillRect(0, 0, width, height);
  if (!chartBars.length) return;
  const count = width < 420 ? 48 : 70, bars = chartBars.slice(-count);
  const allCloses = chartBars.map(b => b.close), fast = emaSeries(allCloses, 20).slice(-count), slow = emaSeries(allCloses, 50).slice(-count);
  $("#ema20-value").textContent = price(fast.at(-1)); $("#ema50-value").textContent = price(slow.at(-1));
  const left = 15, right = 88, top = 15, bottom = 27, volumeHeight = 32;
  const plotW = width - left - right, plotH = height - top - bottom - volumeHeight - 10;
  let min = Math.min(...bars.map(b => b.low), ...(showEMA50 ? slow : []), ...(showEMA20 ? fast : []));
  let max = Math.max(...bars.map(b => b.high), ...(showEMA50 ? slow : []), ...(showEMA20 ? fast : []));
  const margin = (max - min) * .1 || max * .01; min -= margin; max += margin;
  const y = value => top + (max - value) / (max - min) * plotH;
  const step = plotW / bars.length, x = i => left + (i + .5) * step;
  ctx.font = '11px "SFMono-Regular",Consolas,monospace'; ctx.lineWidth = 1;
  for (let j = 0; j <= 4; j++) {
    const yy = top + j / 4 * plotH;
    ctx.strokeStyle = "#354052"; ctx.setLineDash([3, 5]); ctx.beginPath(); ctx.moveTo(left, yy); ctx.lineTo(width - right, yy); ctx.stroke(); ctx.setLineDash([]);
    ctx.fillStyle = "#b8c4d4"; ctx.fillText(price(max - j / 4 * (max - min)), width - right + 9, yy + 3);
  }
  const maxVol = Math.max(...bars.map(b => b.volume)) || 1;
  bars.forEach((b, i) => {
    const bullish = b.close >= b.open, xx = x(i), bodyW = Math.max(2, step * .56);
    ctx.strokeStyle = bullish ? "#72dda8" : "#f08e91"; ctx.fillStyle = ctx.strokeStyle;
    ctx.beginPath(); ctx.moveTo(xx, y(b.high)); ctx.lineTo(xx, y(b.low)); ctx.stroke();
    const yy = Math.min(y(b.open), y(b.close)), hh = Math.max(1, Math.abs(y(b.open) - y(b.close)));
    ctx.fillRect(xx - bodyW / 2, yy, bodyW, hh);
    ctx.fillStyle = bullish ? "#77994e44" : "#a46d5a44";
    const vh = b.volume / maxVol * volumeHeight;
    ctx.fillRect(xx - bodyW / 2, top + plotH + 10 + volumeHeight - vh, bodyW, vh);
  });
  const line = (values, stroke) => { ctx.strokeStyle = stroke; ctx.lineWidth = 1.15; ctx.beginPath(); values.forEach((v, i) => i ? ctx.lineTo(x(i), y(v)) : ctx.moveTo(x(i), y(v))); ctx.stroke(); };
  if (showEMA20) line(fast, "#c7e78a"); if (showEMA50) line(slow, "#9e8fc4");
  const latest = bars.at(-1).close, yy = y(latest);
  ctx.lineWidth = .7; ctx.setLineDash([3, 4]); ctx.strokeStyle = "#85a75e"; ctx.beginPath(); ctx.moveTo(left, yy); ctx.lineTo(width - right + 4, yy); ctx.stroke(); ctx.setLineDash([]);
  ctx.fillStyle = "#3b512b"; ctx.fillRect(width - right + 3, yy - 9, right - 7, 18);
  ctx.fillStyle = "#d0ebad"; ctx.fillText(price(latest), width - right + 7, yy + 3);
  ctx.fillStyle = "#b8c4d4";
  const timeLabels = width < 500 ? 3 : 5;
  for (let j = 0; j < timeLabels; j++) {
    const i = Math.floor(j / (timeLabels - 1) * (bars.length - 1));
    const date = new Date(bars[i].time * 1000);
    const text = date.toLocaleString("zh-CN", {timeZone: "Asia/Shanghai", month: "2-digit", day: "2-digit", ...(interval === "15m" || interval === "1h" ? {hour: "2-digit", minute: "2-digit"} : {})}).replace(/\//g, ".");
    ctx.fillText(text, Math.min(width - right - 25, Math.max(6, x(i) - 18)), height - 9);
  }
  if (hover >= 0 && hover < bars.length) {
    ctx.strokeStyle = "#a4b98c66"; ctx.setLineDash([3, 4]); ctx.beginPath(); ctx.moveTo(x(hover), top); ctx.lineTo(x(hover), height - bottom); ctx.stroke(); ctx.setLineDash([]);
    const b = bars[hover]; $("#chart-tooltip").hidden = false;
    $("#chart-tooltip").textContent = `${stamp(b.time)}  开 ${price(b.open)}  高 ${price(b.high)}  低 ${price(b.low)}  收 ${price(b.close)}`;
  }
  chartGeometry = {left, step, bars};
}

async function handleAction(event) {
  const pageTarget = event.target.closest("[data-page]");
  if (pageTarget) { showPage(pageTarget.dataset.page); return; }
  const el = event.target.closest("[data-action]");
  if (!el) return;
  const {action, symbol, id} = el.dataset;
  try {
    if (action === "select-coin") selectCoin(symbol);
    else if (action === "add-watch") openDialog("watch");
    else if (action === "add-alert") openDialog("alert", symbol || selected);
    else if (action === "live-mode") await toggleMode(false);
    else if (action === "open-report") await openReport(id);
    else if (action === "push-report") {
      if (!state.settings.telegram_configured) { showPage("settings"); toast("请先配置 Telegram Bot Token 和 Chat ID"); return; }
      el.disabled = true; await api("/api/telegram/report", {id: Number(id)}); toast("报告已推送 Telegram");
    } else if (action === "delete-watch") { await api("/api/watchlist/delete", {symbol}); await refreshState(); toast(`已移除 ${base(symbol)}，持仓和预警记录仍然保留`); }
    else if (action === "delete-position") { await api("/api/positions/delete", {id: Number(id)}); await refreshState(); toast("持仓记录已删除"); }
    else if (action === "delete-alert") { await api("/api/alerts/delete", {id: Number(id)}); await refreshState(); toast("预警已删除"); }
  } catch (error) { toast(error.message, true); }
  finally { el.disabled = false; }
}

function bind() {
  $$('[data-icon]').forEach(el => { el.innerHTML = icon(el.dataset.icon); });
  document.addEventListener("click", handleAction);
  $("#mobile-menu").onclick = () => $(".sidebar").classList.toggle("open");
  $("#chart-symbol").onchange = event => selectCoin(event.target.value);
  $$("[data-interval]").forEach(button => button.onclick = () => {
    interval = button.dataset.interval;
    $$("[data-interval]").forEach(b => b.classList.toggle("active", b === button));
    loadChart();
  });
  $("#toggle-ema20").onclick = event => { showEMA20 = !showEMA20; event.currentTarget.classList.toggle("active", showEMA20); drawChart(); };
  $("#toggle-ema50").onclick = event => { showEMA50 = !showEMA50; event.currentTarget.classList.toggle("active", showEMA50); drawChart(); };
  $("#chart-refresh").onclick = async () => { try { await api("/api/refresh", {}); await loadChart(); toast("已请求刷新，监控行情将在下一轮更新"); } catch (error) { toast(error.message, true); } };
  $("#analysis-mode").onchange = () => render();
  $("#news-refresh").onclick = () => loadNews();
  $("#analyze-button").onclick = () => analyze();
  $("#briefing-button").onclick = () => analyze(true);
  $("#history-briefing").onclick = () => analyze(true);
  ["#add-watch-top", "#add-watch-button"].forEach(s => $(s).onclick = () => openDialog("watch"));
  $("#add-position-button").onclick = () => openDialog("position");
  $("#add-alert-button").onclick = () => openDialog("alert");
  $("#dialog-close").onclick = $("#dialog-cancel").onclick = () => $("#form-dialog").close();
  $("#dialog-form").onsubmit = submitDialog;
  $("#settings-form").onsubmit = async event => { event.preventDefault(); const button = event.submitter; button.disabled = true; try { await saveSettings(); } catch (error) { toast(error.message, true); } finally { button.disabled = false; } };
  $("#telegram-test").onclick = async event => { const button = event.currentTarget; button.disabled = true; try { if (await saveSettings(true)) { await api("/api/telegram/test", {}); toast("Telegram 测试消息已送达"); } } catch (error) { toast(error.message, true); } finally { button.disabled = false; } };
  $("#mode-toggle").onclick = async () => { try { await toggleMode(!state.demo); } catch (error) { toast(error.message, true); } };
  $("#market-chart").onmousemove = event => {
    if (!chartGeometry) return;
    const rect = event.currentTarget.getBoundingClientRect();
    const i = Math.floor((event.clientX - rect.left - chartGeometry.left) / chartGeometry.step);
    drawChart(i);
  };
  $("#market-chart").onmouseleave = () => { $("#chart-tooltip").hidden = true; drawChart(); };
  new ResizeObserver(() => drawChart()).observe($("#chart-container"));
  document.addEventListener("visibilitychange", () => { if (!document.hidden) { refreshState(true); loadChart(); } });
}

async function start() {
  bind();
  const clock = () => $("#header-clock").textContent = new Date().toLocaleTimeString("zh-CN", {timeZone: "Asia/Shanghai", hour: "2-digit", minute: "2-digit"}) + " CST";
  clock(); setInterval(clock, 10000);
  await refreshState(); await Promise.allSettled([loadChart(), loadNews()]);
  setInterval(() => { if (!document.hidden) refreshState(true); }, 3000);
  setInterval(() => { if (!document.hidden && page === "dashboard") loadNews(); }, 300000);
  setInterval(() => { if (!document.hidden && page === "dashboard" && !chartLoading) { sparkCache.clear(); loadChart(); } }, 60000);
}
start().catch(error => toast(error.message, true));
