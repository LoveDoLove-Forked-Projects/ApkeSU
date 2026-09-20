'use strict';

const CACHE_NAME = 'apkesu-web-shell-v13';
const SHELL = [
  '/',
  '/index.html',
  '/style.css?v=13',
  '/app.js?v=13',
  '/manifest.webmanifest',
  '/pwa-icon.svg',
  '/pwa-icon-192.png',
  '/pwa-icon-512.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  // Pairing tokens are one-time credentials. Never persist a URL containing a
  // query string in Cache Storage, browser history is cleaned by app.js.
  if (url.search) {
    event.respondWith(fetch(request, { cache: 'no-store' }));
    return;
  }

  // Management data must always come from the live ksud backend. Never let a
  // stale cache pretend that an operation or status query succeeded.
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/webui/')) {
    event.respondWith(fetch(request));
    return;
  }

  event.respondWith(
    fetch(request)
      .then((response) => {
        if (response.ok) {
          const copy = response.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(request, copy));
        }
        return response;
      })
      .catch(() => caches.match(request).then((cached) => cached || caches.match('/index.html'))),
  );
});
