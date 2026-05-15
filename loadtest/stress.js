import http from 'k6/http';
import {check} from 'k6';
import {Counter} from 'k6/metrics';

const errors = new Counter('errors');

export const options = {
    scenarios: {
        ramp_up_reads: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                {duration: '30s', target: 50},
                {duration: '30s', target: 100},
                {duration: '30s', target: 200},
                {duration: '30s', target: 400},
                {duration: '30s', target: 800},
                {duration: '30s', target: 0},
            ],
            exec: 'hammer',
        },
    },
};

// Match Base62Encoder.java alphabet exactly
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

// Hot codes — small pool, will get cached quickly
const HOT_CODES = ['1', '2', '3', '4', '5', '6', '7', '8', '9', 'a'];
const COLD_POOL_SIZE = 100000;  // larger than baseline — more miss pressure

function pickCode() {
    // 20% hot (cache hits), 80% cold (cache misses)
    if (Math.random() < 0.2) {
        return HOT_CODES[Math.floor(Math.random() * HOT_CODES.length)];
    }
    const id = Math.floor(Math.random() * COLD_POOL_SIZE) + 1;
    return toBase62(id);
}

export function hammer() {
    const code = pickCode();
    const res = http.get(`http://localhost:8080/${code}`, {redirects: 0, tags: {name: 'redirect'}});

    check(res, {'ok': (r) => r.status === 301 || r.status === 404});

    if (res.status !== 301 && res.status !== 404) {
        errors.add(1);  // only count actual errors, not expected 404s
    }
}