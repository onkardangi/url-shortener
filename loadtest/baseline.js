import http from 'k6/http';
import {check} from 'k6';
import {Counter, Trend} from 'k6/metrics';

const shortenLatency = new Trend('shorten_latency');
const redirectLatency = new Trend('redirect_latency');
const codesCreated = new Counter('codes_created');
const cacheMisses = new Counter('cache_misses_approx');

export const options = {
    scenarios: {
        writers: {
            executor: 'constant-vus',
            vus: 10,
            duration: '60s',
            exec: 'writeUrls',
        },
        readers: {
            executor: 'constant-vus',
            vus: 50,
            duration: '60s',
            exec: 'readUrls',
            startTime: '5s',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        'shorten_latency': ['p(95)<200', 'p(99)<500'],
        'redirect_latency': ['p(95)<200', 'p(99)<500'],  // relaxed — misses are slower
    },
};

// Hot codes — these exist in the DB and will get cached quickly.
// 20% of read traffic hits these (cache hits).
const HOT_CODES = ['1', '2', '3', '4', '5', '6', '7', '8', '9', 'a'];

// Cold pool — large range of IDs, most won't be cached.
// 80% of read traffic hits this pool (mostly cache misses).
const COLD_POOL_SIZE = 50000;

function pickCode() {
    // 20% chance: pick a hot code (cache hit)
    // 80% chance: pick from cold pool (likely cache miss)
    if (Math.random() < 0.2) {
        return HOT_CODES[Math.floor(Math.random() * HOT_CODES.length)];
    }

    // Base62 encode a random ID in the cold pool.
    // These IDs may or may not exist in the DB — 404s are fine,
    // they still exercise the cache miss path.
    const id = Math.floor(Math.random() * COLD_POOL_SIZE) + 1;
    return toBase62(id);
}

// Match your Base62Encoder.java alphabet exactly
const ALPHABET = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';

function toBase62(id) {
    if (id === 0) return '0';
    let result = '';
    while (id > 0) {
        result = ALPHABET[id % 62] + result;
        id = Math.floor(id / 62);
    }
    return result;
}

export function writeUrls() {
    const payload = JSON.stringify({
        url: `https://example.com/${__VU}/${__ITER}`,
    });
    const res = http.post('http://localhost:8080/shorten', payload, {
        headers: {'Content-Type': 'application/json'},
    });

    shortenLatency.add(res.timings.duration);
    check(res, {'shorten ok': (r) => r.status === 201});

    if (res.status === 201) {
        codesCreated.add(1);
    }
}

export function readUrls() {
    const code = pickCode();
    const res = http.get(`http://localhost:8080/${code}`, {
        redirects: 0,
        tags: {name: 'redirect'}
    });

    redirectLatency.add(res.timings.duration);

    // 404 = code doesn't exist = definite cache miss path exercised
    // 301 = found = may be cache hit or miss
    check(res, {'redirect ok': (r) => r.status === 301 || r.status === 404});

    // Rough cache miss approximation — 404s are always misses
    if (res.status === 404) {
        cacheMisses.add(1);
    }
}