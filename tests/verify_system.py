#!/usr/bin/env python3
"""Query actual backends and preserve raw evidence. No in-memory trace oracle."""
import argparse,base64,json,time,urllib.request,urllib.error,urllib.parse
from pathlib import Path
OUT=Path('evidence');OUT.mkdir(exist_ok=True)

def request(url,body=None):
    data=json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(urllib.request.Request(url,data=data,headers={'Content-Type':'application/json','Accept':'application/json'}),timeout=15) as response:
            raw=response.read();return response.status,json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw=error.read()
        try:value=json.loads(raw)
        except Exception:value=raw.decode(errors='replace')
        return error.code,value

def wait(label,fn,timeout=75):
    start=time.monotonic();last=None
    while time.monotonic()-start<timeout:
        try:
            last=fn()
            if last:return last
        except Exception as error:last=str(error)
        time.sleep(1)
    raise AssertionError(f'{label}: timeout; last={last}')

def save(name,data):(OUT/name).write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
def events():return request('http://127.0.0.1:8080/lab/events')[1]
def processed(order):return next((e for e in events() if e['event']=='worker.processed' and e['order_id']==order),None)
def query(expression):
    status,result=request('http://127.0.0.1:9095/api/v1/query?'+urllib.parse.urlencode({'query':expression}))
    assert status==200,(status,result)
    return result

def identifier(value):
    if not value:return ''
    if len(value) in (16,32) and all(c in '0123456789abcdefABCDEF' for c in value):return value.lower()
    return base64.b64decode(value).hex()

def spans(trace):
    result=[]
    def walk(value,service=''):
        if isinstance(value,dict):
            if 'resource' in value:
                attrs=value['resource'].get('attributes',[])
                service=next((a['value'].get('stringValue','') for a in attrs if a['key']=='service.name'),service)
            if 'spanId' in value and 'name' in value:
                result.append(dict(name=value['name'],service=service,span_id=identifier(value['spanId']),parent=identifier(value.get('parentSpanId','')),start=int(value.get('startTimeUnixNano',0)),end=int(value.get('endTimeUnixNano',0))))
            for child in value.values():walk(child,service)
        elif isinstance(value,list):
            for child in value:walk(child,service)
    walk(trace);return result

def trace(trace_id,name):
    def fetch():
        status,value=request('http://127.0.0.1:3200/api/traces/'+trace_id)
        return value if status==200 and spans(value) else None
    result=wait('Tempo trace '+name,fetch);save('trace-'+name+'.json',result);return spans(result)

def post(order,**extra):
    status,body=request('http://127.0.0.1:8080/orders',dict(id=order,**extra))
    return status,body

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--phase',choices=['full','sampled-out','collector-outage','recovered'],default='full');args=ap.parse_args()
    wait('application ready',lambda:request('http://127.0.0.1:8080/health')[0]==200)
    if args.phase=='collector-outage':
        start=time.monotonic();status,body=post('collector-outage');elapsed=time.monotonic()-start
        assert status==202 and elapsed<8,(status,body,elapsed)
        event=wait('business with collector stopped',lambda:processed('collector-outage'))
        save('collector-outage.json',{'response':body,'worker_event':event,'http_elapsed_seconds':elapsed})
        print('PASS application and real MQ consumer continue while Collector is stopped');return
    if args.phase=='recovered':
        wait('Collector health after restart',lambda:request('http://127.0.0.1:13133/')[0]==200)
        status,body=post('after-outage');assert status==202,body
        event=wait('after-outage consumer',lambda:processed('after-outage'))
        found=trace(body['trace_id'],'after-outage');assert {'book-gateway','book-orders','book-worker'}<={s['service'] for s in found}
        outage=json.loads((OUT/'collector-outage.json').read_text())
        old_code,old_body=request('http://127.0.0.1:3200/api/traces/'+outage['response']['trace_id'])
        save('collector-recovered.json',{'fresh_response':body,'fresh_worker':event,'fresh_trace_services':sorted({s['service'] for s in found}),'outage_trace_status_after_recovery':old_code})
        print('PASS fresh real traces resume after Collector restart; old-trace result recorded without promising lossless recovery');return
    if args.phase=='sampled-out':
        status,body=post('sampled-out');assert status==202,body
        event=wait('unsampled consumer',lambda:processed('sampled-out'))
        assert event['sampled'] is False,event
        request('http://127.0.0.1:8080/lab/flush');time.sleep(5)
        code,value=request('http://127.0.0.1:3200/api/traces/'+body['trace_id'])
        assert code==404,(code,value)
        result={'sampled_out_order':body,'worker_event':event,'tempo_http_status':code,'metrics':query('sum(lab_requests_total{service_name="book-gateway"})')}
        assert result['metrics']['data']['result'],result
        save('sampling-zero.json',result);print('PASS sampling=0: real consumer and metrics continue; Tempo returns 404; logs mark sampled=false');return
    wait('initial metric series',lambda:query('sum(lab_requests_total{service_name="book-gateway"})')['data']['result'])
    time.sleep(3)
    results={}
    for name,options in [('normal',{}),('redis-slow',{'redis_delay_ms':350}),('worker-slow',{'worker_delay_ms':450}),('broken-context',{'propagate':False})]:
        status,body=post(name,**options);assert status==202,(status,body)
        event=wait('worker '+name,lambda:processed(name))
        results[name]={'response':body,'worker_event':event}
        found=trace(body['trace_id'],name)
        assert {'book-gateway','book-orders'}<={s['service'] for s in found},found
        if name!='broken-context':
            assert event['trace_id']==body['trace_id'],(name,event,body)
            assert 'book-worker' in {s['service'] for s in found},found
            producer=next(s for s in found if s['name'].startswith('rocketmq publish'))
            consumer=next(s for s in found if s['name'].startswith('rocketmq process'))
            assert consumer['parent']==producer['span_id'],(producer,consumer)
            assert any(s['span_id']==event['span_id'] for s in found),event
        else:
            assert event['trace_id']!=body['trace_id'],(event,body)
            assert 'book-worker' not in {s['service'] for s in found}
            detached=trace(event['trace_id'],'detached-worker')
            assert 'book-worker' in {s['service'] for s in detached}
        if name=='redis-slow':
            client=next(s for s in found if s['name']=='redis SET order')
            assert (client['end']-client['start'])/1e6>=300,client
            results[name]['injected_span_ms']=(client['end']-client['start'])/1e6
        if name=='worker-slow':
            consumer=next(s for s in found if s['name'].startswith('rocketmq process'))
            assert (consumer['end']-consumer['start'])/1e6>=400,consumer
            results[name]['consumer_span_ms']=(consumer['end']-consumer['start'])/1e6
        results[name]['spans']=found
    for number in range(7):
        status,body=post('slo-ok-'+str(number));assert status==202,body
        time.sleep(.3)
    for number in range(5):
        status,body=post('slo-error-'+str(number),fail=True);assert status==503,body
        time.sleep(1)
    request('http://127.0.0.1:8080/lab/flush')
    metrics=wait('metric counter',lambda:(lambda r:r if r['data']['result'] and float(r['data']['result'][0]['value'][1])>=16 else None)(query('sum(lab_requests_total{service_name="book-gateway"})')))
    save('metrics-requests.json',metrics)
    histogram=query('histogram_quantile(0.95, sum by (le) (rate(lab_http_duration_seconds_bucket{service_name="book-gateway"}[1m])))');save('metrics-p95.json',histogram)
    assert histogram['data']['result'],histogram
    def firing():
        code,value=request('http://127.0.0.1:9095/api/v1/alerts')
        return value if code==200 and any(a['labels'].get('alertname')=='BookFastBurn' and a['state']=='firing' for a in value['data']['alerts']) else None
    alert=wait('Prometheus fast-burn firing',firing);save('alerts.json',alert)
    metric_labels=query('lab_requests_total');save('metric-labels.json',metric_labels)
    assert all('order_id' not in row['metric'] and 'trace_id' not in row['metric'] for row in metric_labels['data']['result'])
    def exemplars():
        expression='lab_http_duration_seconds_bucket{service_name="book-gateway"}'
        code,value=request('http://127.0.0.1:9095/api/v1/query_exemplars?'+urllib.parse.urlencode({'query':expression,'start':time.time()-600,'end':time.time()}))
        return value if code==200 and value.get('data') else None
    samples=wait('real metric exemplars',exemplars);save('exemplars.json',samples)
    exemplar_ids={label['value'] for series in samples['data'] for sample in series['exemplars'] for label in sample['labels'] if label['name']=='trace_id'}
    assert exemplar_ids, samples
    known={e['trace_id'] for e in events()};assert exemplar_ids & known,(exemplar_ids,known)
    save('events.json',events());save('results.json',{'checks':results,'passed':True,'metrics_requests':metrics,'alert':alert,'exemplar_trace_ids':sorted(exemplar_ids)})
    print('PASS actual Java/Redis/RocketMQ/OTLP/Tempo/Prometheus: parentage, logs, delays, broken-context, histogram, low-cardinality labels and firing alert')
if __name__=='__main__':main()
