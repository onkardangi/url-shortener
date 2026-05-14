import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    vus: 1,           // 1 virtual user
    duration: '30s',  // for 30 seconds
    thresholds: {
        http_req_failed: ['rate<0.01'],   // <1% errors
        http_req_duration: ['p(95)<500'], // 95% of requests under 500ms
    },
};

export default function () {
    // Step 1: shorten a URL
    const payload = JSON.stringify({ url: 'https://example.com/page-' + __ITER });
    const headers = { 'Content-Type': 'application/json' };

    const shortenRes = http.post('http://localhost:8080/shorten', payload, { headers });

    check(shortenRes, {
        'shorten status is 201': (r) => r.status === 201,
        'response has code': (r) => r.json('code') !== undefined,
    });

    // Step 2: follow the short URL we just made (don't follow the 301 to the destination)
    const code = shortenRes.json('code');
    if (code) {
        const redirectRes = http.get(`http://localhost:8080/${code}`, { redirects: 0 });
        check(redirectRes, {
            'redirect status is 301': (r) => r.status === 301,
        });
    }

    sleep(1); // pace ourselves — 1 request per second per VU
}