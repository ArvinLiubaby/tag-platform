import * as core from '../core.js';

const { $, esc, api, emptyState } = core;

function kfmt(n) {
  return (n == null ? 0 : Number(n)).toLocaleString('zh-CN');
}

/**
 * 接入监控页：写入通道（当前为 Kafka，后续可扩 HTTP / 文件导入）的运行状态。
 * 只回答三个问题——数据有没有进来、有没有积压、错了多少；broker 级指标交给外部监控。
 */
export function renderMonitor() {
  $('#content').innerHTML =
    '<div class="notice notice-info">接入通道的运行状态。HTTP 开放 API 与 Kafka 推送是两种写入方式，' +
    '写入后的校验、幂等、任务联动与审计完全一致；这里只反映通道是否通畅。</div>' +
    '<div id="monitor-body" style="margin-top:14px"></div>';
  loadKafkaCard();
}

function loadKafkaCard() {
  const host = $('#monitor-body');
  if (!host) return;
  host.innerHTML = '<div class="card"><div class="card-body muted" style="padding:26px;font-size:13px">加载中…</div></div>';
  api('/kafka-status').then(function (d) {
    if (!host || !$('#monitor-body')) return;
    host.innerHTML = kafkaCard(d || {});
    const btn = $('#monitor-refresh');
    if (btn) btn.addEventListener('click', loadKafkaCard);
  }).catch(function (e) {
    if (!host) return;
    host.innerHTML = '<div class="card"><div class="card-body">' + emptyState('加载失败', e.message) + '</div></div>';
  });
}

function kafkaCard(d) {
  const head =
    '<div class="card-head has-divider">' +
    '  <div class="card-title">Kafka 标签推送 <span class="count-chip mono">' + esc(d.topic || '未配置') + '</span></div>' +
    '  <div class="card-actions"><button class="btn btn-ghost btn-sm" id="monitor-refresh">刷新</button></div>' +
    '</div>';
  return '<div class="card">' + head + '<div class="card-body">' + kafkaBody(d) + '</div></div>';
}

function kafkaBody(d) {
  if (!d.enabled) {
    return '<div class="notice notice-warn">Kafka 通道未启用：服务端 <span class="code-inline">TAG_PLATFORM_KAFKA_ENABLED=false</span>。' +
      '开启需设置 <span class="code-inline">TAG_PLATFORM_KAFKA_ENABLED=true</span> 与 ' +
      '<span class="code-inline">TAG_PLATFORM_KAFKA_SERVERS</span> 后重启服务。</div>' +
      '<div class="kafka-meta" style="margin-top:12px">topic <b class="mono">' + esc(d.topic || '-') +
      '</b> · group <b class="mono">' + esc(d.group || '-') + '</b></div>';
  }

  const stateCls = d.snapshot_error ? 'is-bad' : (d.group_state === 'Stable' ? 'is-ok' : 'is-idle');
  const stateText = d.snapshot_error ? '状态查询异常'
    : d.group_state === 'Stable'
      ? (d.lag > 0 ? '消费中 · 有积压' : '消费中')
      : (d.group_state === 'Empty' ? '消费者未在线' : '未知状态（' + esc(d.group_state || '-') + '）');
  const lagCls = d.lag > 1000 ? ' is-bad' : (d.lag > 0 ? ' is-warn' : '');
  const dlqCls = d.dead_letters > 0 ? ' is-warn' : '';
  const rate = d.total_received > 0 ? (d.total_succeeded / d.total_received * 100) : null;
  const rateText = rate == null ? '—' : rate.toFixed(2) + '%';

  return '' +
    '<div class="kafka-head">' +
      '<span class="kafka-state ' + stateCls + '"><i></i>' + stateText + '</span>' +
      '<span class="kafka-meta">group <b class="mono">' + esc(d.group) + '</b></span>' +
    '</div>' +
    '<div class="kafka-grid">' +
      '<div class="kafka-item"><span>分区</span><b>' + kfmt(d.partitions) + '</b></div>' +
      '<div class="kafka-item"><span>未消费积压</span><b class="' + lagCls + '">' + kfmt(d.lag) + '</b></div>' +
      '<div class="kafka-item"><span>死信</span><b class="' + dlqCls + '">' + kfmt(d.dead_letters) + '</b></div>' +
      '<div class="kafka-item"><span title="本次进程内最近一条消息的消费时间，重启后重新计时">最近消费</span><b class="kafka-time">' +
        esc(d.last_consumed_at || (d.total_received > 0 ? '重启后暂无' : '尚无消息')) + '</b></div>' +
    '</div>' +
    '<div class="kafka-counts">' +
      countBlock('本次消费', '进程启动 ' + esc(d.started_at || '-') + ' 起 · 重启清零',
        countRow(d.received, d.succeeded, d.duplicates, d.failed, d.retried), '') +
      countBlock('累计消费', '自 ' + esc(d.total_since || '首次接入') + ' 起 · 跨重启保留',
        countRow(d.total_received, d.total_succeeded, d.total_duplicates, d.total_failed, d.total_retried) +
        '<span class="kafka-rate">成功率 <b>' + rateText + '</b></span>', ' is-total') +
    '</div>' +
    (d.last_error
      ? '<div class="notice notice-warn kafka-last-error">最近错误（' + esc(d.last_error_at || '-') + '）：' + esc(d.last_error) + '</div>'
      : '') +
    (d.snapshot_error
      ? '<div class="notice notice-warn kafka-last-error">' + esc(d.snapshot_error) + '</div>'
      : '') +
    '<div class="kafka-tip">积压 = topic 末位点 − 平台已提交位点；持续增长说明消费跟不上或消费者掉线。' +
    '死信保留原消息与错误原因，排查后可修正后重投。累计计数存于 Redis（键 <span class="code-inline">tp:kafka:stat:*</span>），' +
    '服务重启不清零；Redis 数据被清理后会归零重算。</div>';
}

/** 一组计数块：标题 + 时间口径 + 计数行 */
function countBlock(title, hint, row, extraCls) {
  return '<div class="kafka-count-block' + (extraCls || '') + '">' +
    '<div class="kafka-counts-title"><b>' + title + '</b><span class="kafka-since">' + hint + '</span></div>' +
    '<div class="kafka-count-row">' + row + '</div>' +
    '</div>';
}

function countRow(received, succeeded, duplicates, failed, retried) {
  return '<span class="kafka-count"><b>' + kfmt(received) + '</b>接收</span>' +
    '<span class="kafka-count is-ok"><b>' + kfmt(succeeded) + '</b>写入成功</span>' +
    '<span class="kafka-count"><b>' + kfmt(duplicates) + '</b>幂等重复</span>' +
    '<span class="kafka-count is-bad"><b>' + kfmt(failed) + '</b>失败进死信</span>' +
    '<span class="kafka-count"><b>' + kfmt(retried) + '</b>触发重试</span>';
}
