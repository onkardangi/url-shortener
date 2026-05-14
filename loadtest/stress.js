import http from 'k6/http';

export const options = {
    scenarios: {
        ramp_up_reads: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '30s', target: 50 },
                { duration: '30s', target: 100 },
                { duration: '30s', target: 200 },
                { duration: '30s', target: 400 },
                { duration: '30s', target: 800 },
                { duration: '30s', target: 0 },  // ramp down
            ],
            exec: 'hammer',
        },
    },
};

const CODES = ['1', '2', '3', '4', '5'];

export function hammer() {
    const code = CODES[Math.floor(Math.random() * CODES.length)];
    http.get(`http://localhost:8080/${code}`, { redirects: 0 });
}