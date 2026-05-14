import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// Custom metrics — k6 will track these alongside the built-ins
const shortenLatency = new Trend('shorten_latency');
const redirectLatency = new Trend('redirect_latency');
const codesCreated = new Counter('codes_created');

export const options = {
    scenarios: {
        // Scenario 1: a small pool of writers creating short URLs
        writers: {
            executor: 'constant-vus',
            vus: 10,
            duration: '60s',
            exec: 'writeUrls',
        },
        // Scenario 2: a much bigger pool of readers (realistic skew)
        readers: {
            executor: 'constant-vus',
            vus: 50,
            duration: '60s',
            exec: 'readUrls',
            startTime: '5s',  // give writers a head start so codes exist
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        'shorten_latency': ['p(95)<200', 'p(99)<500'],
        'redirect_latency': ['p(95)<50', 'p(99)<200'],
    },
};

// Shared state across VUs to remember codes we created
// (k6 has no global state by default — workaround below uses a small pool of well-known codes)
const KNOWN_CODES = ['1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c'];

export function writeUrls() {
    const payload = JSON.stringify({
        url: `https://example.com/${__VU}/${__ITER}`,
    });
    const res = http.post('http://localhost:8080/shorten', payload, {
        headers: { 'Content-Type': 'application/json' },
    });

    shortenLatency.add(res.timings.duration);

    check(res, { 'shorten ok': (r) => r.status === 201 });

    if (res.status === 201) {
        codesCreated.add(1);
    }
}

export function readUrls() {
    // Pick a random known code (these get created by writers)
    const code = KNOWN_CODES[Math.floor(Math.random() * KNOWN_CODES.length)];

    const res = http.get(`http://localhost:8080/${code}`, { redirects: 0 });

    redirectLatency.add(res.timings.duration);

    check(res, { 'redirect ok': (r) => r.status === 301 || r.status === 404 });
    // 404 is fine — that code might not have been created yet by writers
}