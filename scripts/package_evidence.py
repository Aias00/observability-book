#!/usr/bin/env python3
"""Publish only synthetic CI evidence together with the exact built EPUB hash."""
from pathlib import Path
import hashlib,json,os,zipfile
ROOT=Path(__file__).resolve().parents[1]
source=ROOT/'.build-evidence';target=ROOT/'site/downloads';info=json.loads((ROOT/'site/build-info.json').read_text())
required=['results.json','sampling-zero.json','exemplars.json','collector-outage.json','collector-recovered.json','events.json','collector.log']
for name in required:
    assert (source/name).is_file(),('missing real evidence',name)
results=json.loads((source/'results.json').read_text());assert results['passed'] is True
commit=(source/'commit.txt').read_text().strip()
if os.environ.get('GITHUB_SHA'):assert commit==os.environ['GITHUB_SHA'],'Experiment evidence must match this publication commit.'
archive=target/'observability-evidence.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
    for path in sorted(source.rglob('*')):
        if path.is_file():z.write(path,path.relative_to(source).as_posix())
validation={'status':'passed','commit':commit,'run_url':f'https://github.com/{os.environ.get("GITHUB_REPOSITORY","Aias00/observability-book")}/actions/runs/{os.environ.get("GITHUB_RUN_ID", "local")}',
'epub_sha256':info['epub_sha256'],'evidence_sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),
'scenarios':{name:{'trace_id':value['response']['trace_id'],'spans':len(value['spans']),'injected_span_ms':value.get('injected_span_ms'),'consumer_span_ms':value.get('consumer_span_ms')} for name,value in results['checks'].items()},
'exemplar_trace_ids':results['exemplar_trace_ids'],'sampling_zero':json.loads((source/'sampling-zero.json').read_text())['tempo_http_status'],
'collector_outage':json.loads((source/'collector-outage.json').read_text()),'collector_recovered':json.loads((source/'collector-recovered.json').read_text()),
'limits':['Three logical Java services share one JVM.','Single-node Redis and RocketMQ; no production HA claim.','Collector debug output is not a Loki/log-search backend.','Prometheus firing is verified; notifications and alert recovery are not automated.','Trace presence after outage is recorded without claiming lossless span recovery.']}
(target/'validation.json').write_text(json.dumps(validation,ensure_ascii=False,indent=2)+'\n')
index=ROOT/'site/index.html';html=index.read_text();html=html.replace('<h2 id="downloads">','<p><a href="downloads/observability-evidence.zip">真实集成证据包</a> · <a href="downloads/validation.json">本次构建验证记录</a> · <a href="downloads/epubcheck.txt">EPUB 格式校验</a></p><h2 id="downloads">');index.write_text(html)
print('Packaged actual Docker/Java/OTLP/backend evidence for commit',commit)
