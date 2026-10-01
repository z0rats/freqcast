/* Interactive replica of Freqcast for the landing page (EN + RU share this file).
   Screens: library (MainScreen), Discover (DiscoverStationsScreen, live Radio Browser API),
   Settings (SettingsScreen, UI only). Playback is real: each station streams its actual URL
   through one <audio> element. Mount: <div id="fc-demo" data-lang="en|ru" data-base="path/to/demo/">. */
(function () {
  'use strict';

  var root = document.getElementById('fc-demo');
  if (!root) return;

  var lang = root.getAttribute('data-lang') === 'ru' ? 'ru' : 'en';
  var base = root.getAttribute('data-base') || 'demo/';

  // Strings copied from res/values/strings.xml and res/values-ru/strings.xml; the `inApp`, `hls`,
  // `live`, `tap` and `caption` keys are demo-only.
  var STR = {
    en: {
      discover: 'Discover stations', search: 'Search stations', curated: 'Curated', playing: 'Playing',
      starting: 'Starting…', paused: 'Paused', failed: 'Connection failed', alarms: 'Alarms',
      settings: 'Settings', more: 'More options', add: 'Add', edit: 'Edit', share: 'Share station',
      del: 'Delete', play: 'Play', pause: 'Pause', undo: 'Undo', deleted: 'Station deleted',
      noResults: 'No stations match your search', noStations: 'No saved stations', back: 'Back', cancel: 'Cancel',
      modeName: 'Name', modeNearby: 'Near me', modeGenre: 'Genre', modeCountry: 'Country',
      hintName: 'Search by station name…', hintGenre: 'Search by genre, e.g. jazz…',
      searchError: 'Search failed. Check your connection and try again', empty: 'No stations found',
      prompt: 'Search the Radio Browser directory to find stations to add',
      browseCountry: 'Popular in %1$s', browseGlobal: 'Popular stations',
      genres: { pop: 'Pop', rock: 'Rock', jazz: 'Jazz', news: 'News', talk: 'Talk', classical: 'Classical', electronic: 'Electronic', dance: 'Dance', 'hip hop': 'Hip Hop', oldies: 'Oldies' },
      added: 'Added', locTitle: 'Use your location?',
      locText: 'Finds stations near you using the Radio Browser directory. Your location is sent only for this search and is never stored.',
      locContinue: 'Continue', locDenied: 'Location permission is needed to find stations near you',
      locUnavailable: 'Couldn’t determine your location. Check that location services are on and try again',
      sslWarn: 'This station’s HTTPS certificate failed validation and it may not play reliably', visit: 'Visit website',
      sGeneral: 'General', sBackup: 'Backup', sSupport: 'Support', sLanguage: 'Language', sSystem: 'System default',
      sMetered: 'Mobile data warning', sBuffer: 'Rewind buffer size', sBufShort: '%1$d MB', sBufValue: '%1$d MB (~%2$d min)',
      sExport: 'Export stations', sImport: 'Import stations', sRestore: 'Restore curated stations',
      sRestored: 'Restored %1$d station(s)', sBattery: 'Disable battery optimization',
      sGithub: 'Report a bug on GitHub', sEmail: 'Send feedback', sVersion: 'Version %1$s',
      inApp: 'This is a demo — available in the app', hls: 'HLS: open in Safari or the app',
      live: 'Live demo', volume: 'Volume (this page only, not part of the app)', tap: 'Tap ▶ — it really plays',
      caption: 'Not a video: these are live streams and the live Radio Browser directory.',
      label: 'Freqcast interactive demo'
    },
    ru: {
      discover: 'Найти станции', search: 'Поиск станций', curated: 'Подборка', playing: 'Воспроизведение',
      starting: 'Запуск…', paused: 'Пауза', failed: 'Не удалось подключиться', alarms: 'Будильники',
      settings: 'Настройки', more: 'Ещё', add: 'Добавить', edit: 'Редактировать', share: 'Поделиться станцией',
      del: 'Удалить', play: 'Воспроизвести', pause: 'Пауза', undo: 'Отменить', deleted: 'Станция удалена',
      noResults: 'Ничего не найдено по запросу', noStations: 'Нет сохранённых станций', back: 'Назад', cancel: 'Отмена',
      modeName: 'Название', modeNearby: 'Рядом со мной', modeGenre: 'Жанр', modeCountry: 'Страна',
      hintName: 'Поиск по названию станции…', hintGenre: 'Поиск по жанру, напр. джаз…',
      searchError: 'Ошибка поиска. Проверьте соединение и попробуйте снова', empty: 'Станции не найдены',
      prompt: 'Найдите станции в каталоге Radio Browser, чтобы добавить их',
      browseCountry: 'Популярно в стране: %1$s', browseGlobal: 'Популярные станции',
      genres: { pop: 'Поп', rock: 'Рок', jazz: 'Джаз', news: 'Новости', talk: 'Разговорное', classical: 'Классика', electronic: 'Электроника', dance: 'Танцевальная', 'hip hop': 'Хип-хоп', oldies: 'Ретро' },
      added: 'Добавлено', locTitle: 'Использовать геолокацию?',
      locText: 'Поиск станций рядом с вами через каталог Radio Browser. Ваше местоположение отправляется только для этого поиска и никогда не сохраняется.',
      locContinue: 'Продолжить', locDenied: 'Для поиска станций рядом с вами нужен доступ к геолокации',
      locUnavailable: 'Не удалось определить местоположение. Проверьте, включена ли геолокация, и попробуйте снова',
      sslWarn: 'HTTPS-сертификат этой станции не прошёл проверку — она может воспроизводиться нестабильно', visit: 'Открыть сайт',
      sGeneral: 'Основные', sBackup: 'Резервная копия', sSupport: 'Поддержка', sLanguage: 'Язык', sSystem: 'Как в системе',
      sMetered: 'Предупреждение о мобильном интернете', sBuffer: 'Размер буфера перемотки', sBufShort: '%1$d МБ', sBufValue: '%1$d МБ (~%2$d мин)',
      sExport: 'Экспорт станций', sImport: 'Импорт станций', sRestore: 'Восстановить подборку станций',
      sRestored: 'Восстановлено станций: %1$d', sBattery: 'Отключить оптимизацию батареи',
      sGithub: 'Сообщить об ошибке на GitHub', sEmail: 'Отправить отзыв', sVersion: 'Версия %1$s',
      inApp: 'Это демо — в приложении это работает', hls: 'HLS: откройте в Safari или в приложении',
      live: 'Живое демо', volume: 'Громкость (только для этой страницы, не часть приложения)', tap: 'Нажмите ▶ — играет по-настоящему',
      caption: 'Это не видео: здесь живые эфиры и живой каталог Radio Browser.',
      label: 'Интерактивная демонстрация Freqcast'
    }
  }[lang];

  function fmt(s) {
    var a = arguments;
    return s.replace(/%(\d)\$[sd]/g, function (_, n) { return a[+n]; });
  }

  // CuratedStations.pack, verbatim (names, urls, descriptions), with the bundled icons.
  var CURATED = [
    { name: 'Радио Зимы не будет', desc: 'Independent Radio', icon: 'znb.png', url: 'https://server.radioznb.ru/listen/radioznb-live/radio.mp3' },
    { name: 'KRTD Radio', desc: 'Independent Radio · Electronic', icon: 'krtd.png', url: 'https://krtd.049406.xyz/listen/krtd/high' },
    { name: 'KURS Radio', desc: 'Independent Radio · Moscow', icon: 'kurs.png', url: 'https://listen9.myradio24.com/kursradio' },
    { name: 'HKCR', desc: 'Underground Music · Hong Kong', icon: 'hkcr.png', url: 'https://stream-test.hkcr.live/hls/main.m3u8', hls: true },
    { name: 'SURPRISE.FM', desc: 'Radio, Shows & Podcasts', icon: 'surprise.png', url: 'https://listen9.myradio24.com/surprise' }
  ];

  var GENRES = ['pop', 'rock', 'jazz', 'news', 'talk', 'classical', 'electronic', 'dance', 'hip hop', 'oldies'];
  var COUNTRIES = [['RU', 'Russia'], ['BY', 'Belarus'], ['US', 'United States'], ['ES', 'Spain'], ['DE', 'Germany'],
                   ['FR', 'France'], ['IT', 'Italy'], ['GB', 'United Kingdom'], ['PL', 'Poland'], ['CN', 'China']];
  var BUFFERS = [[15, 12], [30, 25], [60, 50], [120, 100]];
  var LANGS = [STR.sSystem, 'English', 'Español', 'Русский', '中文'];
  var APP_VERSION = '3.16.0';

  // ---- layout constants (dp), same as the Compose code ----
  var ROW_H = 80, ROW_GAP = 8, LIST_TOP = 8, LIST_BOTTOM = 88;
  var REVEAL = 184 + 8;          // StationItem: revealThreshold + cardSpacing
  var MINI_W = 360, MINI_GAP = 14;
  var DEV_W = 414, DEV_H = 782;
  var LONG_PRESS_MS = 400, SLOP = 8;

  // ---- icons ----
  var I = {
    discover: '<svg viewBox="0 0 24 24"><g fill="none" stroke="currentColor" stroke-width="2"><circle cx="10.5" cy="10.5" r="6.5"/><path d="M4 10.5h13M10.5 4c-2 2-2.5 4-2.5 6.5s.5 4.5 2.5 6.5M10.5 4c2 2 2.5 4 2.5 6.5"/><circle cx="16" cy="16" r="3.4" fill="#2a1f18"/><path d="M18.5 18.5L22 22" stroke-linecap="round"/></g></svg>',
    globe: '<svg viewBox="0 0 24 24"><g fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c-3 3-3.5 6-3.5 9s.5 6 3.5 9M12 3c3 3 3.5 6 3.5 9s-.5 6-3.5 9"/></g></svg>',
    more: '<svg viewBox="0 0 24 24"><path d="M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/></svg>',
    add: '<svg viewBox="0 0 24 24"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/></svg>',
    edit: '<svg viewBox="0 0 24 24"><path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"/></svg>',
    share: '<svg viewBox="0 0 24 24"><path d="M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92 1.61 0 2.92-1.31 2.92-2.92s-1.31-2.92-2.92-2.92z"/></svg>',
    del: '<svg viewBox="0 0 24 24"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>',
    playC: '<svg viewBox="0 0 24 24"><path d="M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM10,16.5v-9l6,4.5 -6,4.5z"/></svg>',
    pauseC: '<svg viewBox="0 0 24 24"><path d="M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM11,16H9V8h2v8zM15,16h-2V8h2v8z"/></svg>',
    wifi: '<svg viewBox="0 0 24 24"><path d="M1 9l2 2c4.97-4.97 13.03-4.97 18 0l2-2C16.93 2.93 7.08 2.93 1 9zm8 8l3 3 3-3c-1.65-1.66-4.34-1.66-6 0zm-4-4l2 2c2.76-2.76 7.24-2.76 10 0l2-2C15.14 9.14 8.87 9.14 5 13z"/></svg>',
    signal: '<svg viewBox="0 0 24 24"><path d="M2 22h20V2z"/></svg>',
    volUp: '<svg viewBox="0 0 24 24"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"/></svg>',
    volOff: '<svg viewBox="0 0 24 24"><path d="M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z"/></svg>',
    back: '<svg viewBox="0 0 24 24"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg>',
    pin: '<svg viewBox="0 0 24 24"><path d="M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z"/></svg>',
    check: '<svg viewBox="0 0 24 24"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg>',
    star: '<svg viewBox="0 0 24 24"><path d="M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z"/></svg>',
    warn: '<svg viewBox="0 0 24 24"><path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z"/></svg>',
    open: '<svg viewBox="0 0 24 24"><path d="M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z"/></svg>',
    drop: '<svg viewBox="0 0 24 24"><path d="M7 10l5 5 5-5z"/></svg>',
    cell: '<svg viewBox="0 0 24 24"><path d="M17 4h3v16h-3V4zM5 14h3v6H5v-6zm6-5h3v11h-3V9z"/></svg>',
    history: '<svg viewBox="0 0 24 24"><path d="M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z"/></svg>',
    up: '<svg viewBox="0 0 24 24"><path d="M9 16h6v-6h4l-7-7-7 7h4zm-4 2h14v2H5z"/></svg>',
    down: '<svg viewBox="0 0 24 24"><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"/></svg>',
    battery: '<svg viewBox="0 0 24 24"><path d="M15.67 4H14V2h-4v2H8.33C7.6 4 7 4.6 7 5.33v15.33C7 21.4 7.6 22 8.33 22h7.33c.74 0 1.34-.6 1.34-1.33V5.33C17 4.6 16.4 4 15.67 4zM11 20v-5.5H9L13 7v5.5h2L11 20z"/></svg>',
    bug: '<svg viewBox="0 0 24 24"><path d="M20 8h-2.81c-.45-.78-1.07-1.45-1.82-1.96L17 4.41 15.59 3l-2.17 2.17C12.96 5.06 12.49 5 12 5c-.49 0-.96.06-1.41.17L8.41 3 7 4.41l1.62 1.63C7.88 6.55 7.26 7.22 6.81 8H4v2h2.09c-.05.33-.09.66-.09 1v1H4v2h2v1c0 .34.04.67.09 1H4v2h2.81c1.04 1.79 2.97 3 5.19 3s4.15-1.21 5.19-3H20v-2h-2.09c.05-.33.09-.66.09-1v-1h2v-2h-2v-1c0-.34-.04-.67-.09-1H20V8zm-6 8h-4v-2h4v2zm0-4h-4v-2h4v2z"/></svg>',
    feedback: '<svg viewBox="0 0 24 24"><path d="M22 2H2.01L2 22l4-4h16V2zm-9 12h-2v-2h2v2zm0-4h-2V6h2v4z"/></svg>'
  };

  function el(tag, cls, html) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html != null) e.innerHTML = html;
    return e;
  }
  function txt(tag, cls, text) { var e = el(tag, cls); e.textContent = text; return e; }
  function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function isHttp(u) { return /^https?:\/\//i.test(u || ''); }

  // ---- state ----
  var nextId = 1;
  var stations = [];                          // display order (mutated by reorder/delete/undo/add)
  var rows = {};                              // id -> row object
  var currentId = null;                       // MainViewModel.currentPlayingStationId
  var status = 'paused';                      // of the current station: playing | starting | paused | error
  var errorText = null;
  var query = '';
  var hintDone = false, hintCancelled = false, interacted = false;
  var scale = 1;
  var curatedStations = CURATED.map(function (c) { c.id = nextId++; c.curated = true; return c; });
  stations = curatedStations.slice();

  function byId(id) { for (var i = 0; i < stations.length; i++) if (stations[i].id === id) return stations[i]; return null; }
  function byUrl(u) { for (var i = 0; i < stations.length; i++) if (stations[i].url === u) return stations[i]; return null; }
  function nameTaken(n) { for (var i = 0; i < stations.length; i++) if (stations[i].name === n) return true; return false; }
  function visible() {
    var q = query.trim().toLowerCase();
    if (!q) return stations;
    return stations.filter(function (s) { return s.name.toLowerCase().indexOf(q) >= 0 || (s.desc || '').toLowerCase().indexOf(q) >= 0; });
  }

  // ---- DOM skeleton ----
  root.innerHTML = '';
  root.setAttribute('role', 'group');
  root.setAttribute('aria-label', STR.label);

  // Page-level control strip above the phone: "this is a demo" tag + a volume slider. It lives outside
  // the device frame on purpose, so it reads as part of the website, not of the app.
  var livePill = el('div', 'fc-live');
  livePill.appendChild(el('span', 'fc-live-dot'));
  livePill.appendChild(txt('span', 'fc-live-text', STR.live));
  root.appendChild(livePill);

  var VOL_KEY = 'fcDemoVolume';
  var vol = 55;                                   // slider position 0-100 (squared → amplitude, finer at the quiet end)
  try { var saved = parseInt(localStorage.getItem(VOL_KEY), 10); if (saved >= 0 && saved <= 100) vol = saved; } catch (e) { /* storage unavailable */ }
  var canSetVolume = (function () { var t = new Audio(); t.volume = 0.5; return t.volume === 0.5; })();   // iOS keeps it read-only

  var volBtn, volInput;
  function applyVolume() {
    audio.volume = Math.pow(vol / 100, 2);
    audio.muted = vol === 0;
    volBtn.innerHTML = vol === 0 ? I.volOff : I.volUp;
    volInput.value = String(vol);
    volInput.style.setProperty('--fc-vol', vol + '%');
    try { localStorage.setItem(VOL_KEY, String(vol)); } catch (e) { /* ignore */ }
  }
  var lastVol = vol || 55;
  var audio = new Audio();
  audio.preload = 'none';
  if (canSetVolume) {
    var volBox = el('div', 'fc-vol');
    volBox.title = STR.volume;
    volBtn = el('button', 'fc-vol-btn');
    volBtn.type = 'button';
    volBtn.setAttribute('aria-label', STR.volume);
    volInput = document.createElement('input');
    volInput.type = 'range'; volInput.min = '0'; volInput.max = '100'; volInput.step = '1';
    volInput.setAttribute('aria-label', STR.volume);
    volBox.appendChild(volBtn); volBox.appendChild(volInput);
    livePill.appendChild(el('span', 'fc-live-sep'));
    livePill.appendChild(volBox);
    volInput.addEventListener('input', function () { vol = +volInput.value; if (vol) lastVol = vol; applyVolume(); });
    volBtn.addEventListener('click', function () { if (vol === 0) vol = lastVol; else { lastVol = vol; vol = 0; } applyVolume(); });
    applyVolume();
  }

  var stage = el('div', 'fc-stage');
  var device = el('div', 'fc-device fc-idle');
  var screen = el('div', 'fc-screen');
  device.appendChild(screen);
  stage.appendChild(device);
  root.appendChild(stage);

  var clock = (function () { var n = new Date(), m = n.getMinutes(); return n.getHours() + ':' + (m < 10 ? '0' : '') + m; })();
  function makeStatusBar() {
    return el('div', 'fc-status',
      '<span>' + clock + '</span><span class="fc-status-icons" aria-hidden="true">' + I.signal + I.wifi + '<span class="fc-battery"></span></span>');
  }
  screen.appendChild(makeStatusBar());

  var main = el('div', 'fc-main');
  screen.appendChild(main);

  var top = el('div', 'fc-top');
  var chip = el('button', 'fc-chip', I.discover + '<span>' + esc(STR.discover) + '</span>');
  var moreBtn = el('button', 'fc-icon-btn', I.more);
  moreBtn.setAttribute('aria-label', STR.more);
  moreBtn.setAttribute('aria-haspopup', 'menu');
  top.appendChild(chip);
  top.appendChild(moreBtn);
  main.appendChild(top);

  var menu = el('div', 'fc-menu');
  menu.setAttribute('role', 'menu');
  menu.hidden = true;
  function menuItem(label, onClick) {
    var b = txt('button', null, label);
    b.setAttribute('role', 'menuitem');
    b.addEventListener('click', function () { closeMenu(); onClick(); });
    menu.appendChild(b);
  }
  menuItem(STR.alarms, function () { notice(); });
  menuItem(STR.settings, function () { openSettings(); });
  main.appendChild(menu);

  var searchWrap = el('div', 'fc-search-wrap');
  var searchField = el('div', 'fc-search-field');
  var input = document.createElement('input');
  input.type = 'text';
  input.placeholder = STR.search;
  input.setAttribute('aria-label', STR.search);
  input.autocomplete = 'off';
  input.spellcheck = false;
  searchField.appendChild(input);
  var searchPad = el('div', 'fc-search');
  searchPad.appendChild(searchField);
  searchWrap.appendChild(searchPad);
  main.appendChild(searchWrap);

  var list = el('div', 'fc-list');
  list.setAttribute('role', 'list');
  var inner = el('div', 'fc-list-inner');
  list.appendChild(inner);
  main.appendChild(list);

  var noResults = txt('div', 'fc-noresults', STR.noResults);
  main.insertBefore(noResults, list);
  var empty = el('div', 'fc-empty',
    '<div class="fc-empty-icon" aria-hidden="true">📻</div><div class="fc-empty-title">Freqcast</div><div class="fc-empty-msg">' +
    esc(STR.noStations) + '</div>');
  main.appendChild(empty);

  var fab = el('button', 'fc-fab', I.add);
  fab.setAttribute('aria-label', STR.add);
  main.appendChild(fab);

  var snack = el('div', 'fc-snack');
  snack.setAttribute('role', 'status');
  var snackText = el('span');
  var snackBtn = el('button');
  snackBtn.hidden = true;
  snack.appendChild(snackText);
  snack.appendChild(snackBtn);
  main.appendChild(snack);

  var bottom = el('div', 'fc-bottom');
  var gesture = el('div', 'fc-gesture');
  var miniClip = el('div', 'fc-mini-clip');
  var miniTrack = el('div', 'fc-mini-track');
  var miniCards = [0, 1, 2].map(function (i) {
    var c = el('div', 'fc-mini' + (i === 1 ? '' : ' fc-mini-preview'),
      '<div class="fc-mini-text"><div class="fc-mini-name"></div><div class="fc-mini-status-row"><span class="fc-eq" hidden><i></i><i></i><i></i></span><span class="fc-mini-status"></span></div></div>' +
      '<button class="fc-mini-play" tabindex="' + (i === 1 ? '0' : '-1') + '"></button>');
    c.style.width = MINI_W + 'px';
    c.style.marginRight = i < 2 ? MINI_GAP + 'px' : '0';
    miniTrack.appendChild(c);
    return c;
  });
  miniClip.appendChild(miniTrack);
  bottom.appendChild(gesture);
  bottom.appendChild(miniClip);
  screen.appendChild(bottom);

  // Android Toast, used for "this part isn't in the demo" and Settings confirmations
  var toast = el('div', 'fc-toast');
  toast.setAttribute('role', 'status');
  screen.appendChild(toast);
  var toastTimer = null;
  function showToast(msg, ms) {
    clearTimeout(toastTimer);
    toast.textContent = msg;
    toast.classList.add('fc-show');
    toastTimer = setTimeout(function () { toast.classList.remove('fc-show'); }, ms || 2600);
  }
  function notice() { showToast(STR.inApp); }

  // ---- library rows ----
  function emojiIcon() { return txt('span', 'fc-icon fc-icon-emoji', '📻'); }

  function buildRow(s) {
    var r = { s: s, off: 0, open: false, removed: false };
    var row = el('div', 'fc-row');
    row.setAttribute('role', 'listitem');
    var actions = el('div', 'fc-actions');
    var bEdit = el('button', 'fc-act fc-act-edit', I.edit); bEdit.setAttribute('aria-label', STR.edit);
    var bShare = el('button', 'fc-act fc-act-share', I.share); bShare.setAttribute('aria-label', STR.share);
    var bDel = el('button', 'fc-act fc-act-delete', I.del); bDel.setAttribute('aria-label', STR.del);
    actions.appendChild(bEdit); actions.appendChild(bShare); actions.appendChild(bDel);

    var card = el('div', 'fc-card');
    var icon;
    if (s.icon) {
      icon = el('img', 'fc-icon');
      icon.alt = ''; icon.draggable = false; icon.src = base + s.icon;
    } else if (s.favicon && isHttp(s.favicon)) {
      // Discover adds download the directory favicon and keep the emoji if that fails
      icon = el('img', 'fc-icon');
      icon.alt = ''; icon.draggable = false; icon.referrerPolicy = 'no-referrer'; icon.src = s.favicon;
      icon.addEventListener('error', function () { if (icon.parentNode) icon.parentNode.replaceChild(emojiIcon(), icon); });
    } else {
      icon = emojiIcon();
    }
    var text = el('div', 'fc-text');
    var nameRow = el('div', 'fc-name-row');
    nameRow.appendChild(txt('span', 'fc-name', s.name));
    if (s.curated) nameRow.appendChild(txt('span', 'fc-badge', STR.curated));
    var subRow = el('div', 'fc-sub-row');
    var eq = el('span', 'fc-eq', '<i></i><i></i><i></i>'); eq.hidden = true;
    var sub = el('span', 'fc-sub');
    subRow.appendChild(eq); subRow.appendChild(sub);
    text.appendChild(nameRow); text.appendChild(subRow);
    var play = el('button', 'fc-play');
    card.appendChild(icon); card.appendChild(text); card.appendChild(play);
    row.appendChild(actions);
    row.appendChild(card);
    inner.appendChild(row);

    r.el = row; r.card = card; r.actions = actions; r.eq = eq; r.sub = sub; r.play = play;

    play.addEventListener('click', function () {
      if (suppressClick) return;
      if (r.open) { setOffset(r, 0, true); return; }
      togglePlay(s.id);
    });
    card.addEventListener('click', function (e) {
      if (suppressClick) return;
      if (r.open && !e.target.closest('.fc-play')) setOffset(r, 0, true);
    });
    bEdit.addEventListener('click', function () { setOffset(r, 0, true); notice(); });
    bShare.addEventListener('click', function () { setOffset(r, 0, true); notice(); });
    bDel.addEventListener('click', function () { setOffset(r, 0, true); deleteStation(s.id); });

    attachGestures(r);
    rows[s.id] = r;
    return r;
  }
  stations.forEach(buildRow);

  // animate: true → settle with the card's CSS transition; falsy → jump (used while dragging or resetting)
  function setOffset(r, off, animate) {
    r.off = off;
    r.open = off < -REVEAL / 2;
    r.card.style.transform = off ? 'translateX(' + off + 'px)' : '';
    if (off < 0) r.actions.style.visibility = 'visible';
    else if (animate) setTimeout(function () { if (r.off === 0) r.actions.style.visibility = 'hidden'; }, 420);
    else r.actions.style.visibility = 'hidden';
  }
  function settleRow(r) { setOffset(r, r.off < -REVEAL / 2 ? -REVEAL : 0, true); }

  // ---- layout / render ----
  function layout() {
    var vis = visible();
    var shown = {};
    vis.forEach(function (s, i) { shown[s.id] = i; });
    Object.keys(rows).forEach(function (k) {
      var r = rows[k];
      if (r.removed) return;
      if (shown[r.s.id] == null) { r.el.classList.add('fc-gone'); return; }
      r.el.classList.remove('fc-gone');
      if (!r.el.classList.contains('fc-lifted')) r.el.style.top = (LIST_TOP + shown[r.s.id] * (ROW_H + ROW_GAP)) + 'px';
    });
    inner.style.height = (vis.length ? LIST_TOP + vis.length * (ROW_H + ROW_GAP) - ROW_GAP + LIST_BOTTOM : 0) + 'px';
    empty.classList.toggle('fc-show', stations.length === 0);
    noResults.classList.toggle('fc-show', stations.length > 0 && vis.length === 0);
    list.style.display = vis.length ? '' : 'none';
    // TextField is only composed once the library is larger than 4 (StationListPane)
    searchWrap.classList.toggle('fc-collapsed', stations.length <= 4);
  }

  function statusText(st) {
    if (st === 'playing') return STR.playing;
    if (st === 'starting') return STR.starting;
    if (st === 'error') return errorText || STR.failed;
    return STR.paused;
  }

  function render() {
    Object.keys(rows).forEach(function (k) {
      var r = rows[k], s = r.s;
      if (r.removed) return;
      var active = s.id === currentId;
      var st = active ? status : 'paused';
      r.el.classList.toggle('fc-active', active);
      r.eq.hidden = !(active && st === 'playing');
      // StationItem: playing/starting/error replace the description; paused shows it (or the URL)
      r.sub.textContent = active && st !== 'paused' ? statusText(st) : (s.desc || s.url);
      var isPlaying = active && st === 'playing';
      r.play.innerHTML = isPlaying ? I.pauseC : I.playC;
      r.play.setAttribute('aria-label', isPlaying ? STR.pause : STR.play);
    });
    renderMini();
  }

  function renderMini() {
    var cur = currentId != null ? byId(currentId) : null;
    screen.classList.toggle('fc-has-mini', !!cur);
    if (!cur) return;
    var idx = stations.indexOf(cur);
    var prev = idx > 0 ? stations[idx - 1] : null;
    var next = idx < stations.length - 1 ? stations[idx + 1] : null;
    [prev, cur, next].forEach(function (s, i) {
      var c = miniCards[i];
      c.style.visibility = s ? '' : 'hidden';
      if (!s) return;
      c.querySelector('.fc-mini-name').textContent = s.name;
      var isCur = i === 1;
      var st = isCur ? status : 'paused';
      c.querySelector('.fc-mini-status').textContent = statusText(st);
      c.querySelector('.fc-eq').hidden = !(isCur && st === 'playing');
      if (isCur) {
        var b = c.querySelector('.fc-mini-play');
        b.innerHTML = st === 'playing' ? I.pauseC : I.playC;
        b.setAttribute('aria-label', st === 'playing' ? STR.pause : STR.play);
      }
    });
    miniTrack.style.transition = 'none';
    miniTrack.style.transform = 'translateX(' + (-(MINI_W + MINI_GAP)) + 'px)';
  }

  // ---- playback ----
  function stopAudio() {
    audio.pause();
    audio.removeAttribute('src');
    try { audio.load(); } catch (e) { /* ignore */ }
  }

  function start(id) {
    var s = byId(id);
    if (!s) return;
    stopAudio();
    currentId = id;
    errorText = null;
    if (s.hls && !audio.canPlayType('application/vnd.apple.mpegurl')) {
      status = 'error';
      errorText = STR.hls;
      render();
      return;
    }
    status = 'starting';
    audio.src = s.url;
    var p = audio.play();
    if (p && p.catch) p.catch(function () { if (currentId === id && audio.getAttribute('src')) { status = 'error'; render(); } });
    render();
  }

  function togglePlay(id) {
    markInteracted();
    cancelHint();
    if (id === currentId && (status === 'playing' || status === 'starting')) {
      stopAudio();
      status = 'paused';
      render();
    } else {
      start(id);
    }
  }

  audio.addEventListener('playing', function () { if (audio.getAttribute('src')) { status = 'playing'; render(); } });
  audio.addEventListener('waiting', function () { if (audio.getAttribute('src') && status === 'playing') { status = 'starting'; render(); } });
  audio.addEventListener('error', function () { if (audio.getAttribute('src')) { status = 'error'; errorText = null; render(); } });

  miniCards[1].querySelector('.fc-mini-play').addEventListener('click', function (e) {
    e.stopPropagation();
    if (miniMoved) return;
    if (currentId != null) togglePlay(currentId);
  });

  // ---- snackbar ----
  var snackTimer = null;
  function showSnack(msg, actionLabel, onAction, ms) {
    clearTimeout(snackTimer);
    snackText.textContent = msg;
    snackBtn.hidden = !actionLabel;
    snackBtn.textContent = actionLabel || '';
    snackBtn.onclick = actionLabel ? function () { hideSnack(); onAction(); } : null;
    snack.classList.add('fc-show');
    snackTimer = setTimeout(hideSnack, ms || 4000);
  }
  function hideSnack() { clearTimeout(snackTimer); snack.classList.remove('fc-show'); }

  fab.addEventListener('click', notice);

  function closeMenu() { menu.hidden = true; moreBtn.setAttribute('aria-expanded', 'false'); }
  moreBtn.addEventListener('click', function (e) {
    e.stopPropagation();
    menu.hidden = !menu.hidden;
    moreBtn.setAttribute('aria-expanded', String(!menu.hidden));
  });
  document.addEventListener('click', function (e) { if (!menu.hidden && !menu.contains(e.target)) closeMenu(); });

  // ---- delete + undo (MainViewModel.deleteStation / undoDelete) ----
  function deleteStation(id) {
    var s = byId(id);
    if (!s) return;
    var index = stations.indexOf(s);
    var r = rows[id];
    if (currentId === id) { stopAudio(); currentId = null; status = 'paused'; }
    stations.splice(index, 1);
    r.removed = true;
    r.el.classList.add('fc-removing');
    setTimeout(function () { if (r.removed) r.el.classList.add('fc-gone'); }, 200);
    layout();
    render();
    showSnack(STR.deleted, STR.undo, function () {
      stations.splice(Math.min(index, stations.length), 0, s);   // original slot, same as restoreStation()
      r.removed = false;
      r.el.classList.remove('fc-removing', 'fc-gone');
      setOffset(r, 0);
      layout();
      render();
    });
  }

  // RadioStationRepository.insertStationIfAbsent(): appended at the end; the same url is "already
  // saved", a name clash with a different station becomes "Name (2)", "(3)", ...
  function insertStation(data) {
    var existing = byUrl(data.url);
    if (existing) return existing;
    var name = data.name, n = 2;
    while (nameTaken(name)) name = data.name + ' (' + (n++) + ')';
    var s = { id: nextId++, name: name, desc: data.desc || '', url: data.url, hls: !!data.hls, favicon: data.favicon || '', curated: false };
    stations.push(s);
    buildRow(s);
    layout();
    render();
    return s;
  }

  // ---- search ----
  input.addEventListener('input', function () { query = input.value; layout(); list.scrollTop = 0; });

  // ---- gestures: swipe-to-reveal + long-press reorder ----
  var suppressClick = false;
  var reorder = null;

  list.addEventListener('touchmove', function (e) { if (reorder) e.preventDefault(); }, { passive: false });

  function attachGestures(r) {
    var card = r.card;
    var sx = 0, sy = 0, startOff = 0, mode = null, pid = null, lp = null, startScroll = 0, startTop = 0;

    card.addEventListener('pointerdown', function (e) {
      if (e.button !== 0 || e.target.closest('.fc-play')) return;
      cancelHint();
      sx = e.clientX / scale; sy = e.clientY / scale;
      startOff = r.off; mode = null; pid = e.pointerId;
      clearTimeout(lp);
      if (!query.trim() && !r.open) {
        lp = setTimeout(function () {
          mode = 'reorder';
          try { card.setPointerCapture(pid); } catch (err) { /* ignore */ }
          startScroll = list.scrollTop;
          startTop = LIST_TOP + stations.indexOf(r.s) * (ROW_H + ROW_GAP);
          reorder = r;
          r.el.classList.add('fc-lifted');
        }, LONG_PRESS_MS);
      }
    });

    card.addEventListener('pointermove', function (e) {
      if (pid !== e.pointerId) return;
      var dx = e.clientX / scale - sx, dy = e.clientY / scale - sy;
      if (mode === null) {
        if (Math.abs(dx) < SLOP && Math.abs(dy) < SLOP) return;
        clearTimeout(lp);
        if (Math.abs(dx) > Math.abs(dy)) {
          mode = 'swipe';
          try { card.setPointerCapture(pid); } catch (err) { /* ignore */ }
          r.el.classList.add('fc-swiping');
        } else {
          mode = 'scroll';
        }
      }
      if (mode === 'swipe') {
        setOffset(r, Math.max(-REVEAL, Math.min(0, startOff + dx)));
      } else if (mode === 'reorder') {
        dragTo(r, startTop + dy + (list.scrollTop - startScroll), e);
      }
    });

    function end(e) {
      if (pid !== e.pointerId) return;
      clearTimeout(lp);
      if (mode === 'swipe') {
        r.el.classList.remove('fc-swiping');
        settleRow(r);
        suppressNextClick();
      } else if (mode === 'reorder') {
        r.el.classList.remove('fc-lifted');
        reorder = null;
        layout();
        suppressNextClick();
      }
      mode = null; pid = null;
    }
    card.addEventListener('pointerup', end);
    card.addEventListener('pointercancel', end);
    card.addEventListener('contextmenu', function (e) { if (mode === 'reorder') e.preventDefault(); });
  }

  function suppressNextClick() { suppressClick = true; setTimeout(function () { suppressClick = false; }, 0); }

  // DragDropState.settle(): swap once the dragged card's leading edge fully crosses the
  // neighbor's matching edge, so a hovering finger can't flip-flop a pair.
  function dragTo(r, top, e) {
    r.el.style.top = top + 'px';
    var idx = stations.indexOf(r.s);
    var swapped = false;
    if (idx > 0 && top < LIST_TOP + (idx - 1) * (ROW_H + ROW_GAP)) {
      stations.splice(idx, 1); stations.splice(idx - 1, 0, r.s); swapped = true;
    } else if (idx < stations.length - 1 && top > LIST_TOP + (idx + 1) * (ROW_H + ROW_GAP)) {
      stations.splice(idx, 1); stations.splice(idx + 1, 0, r.s); swapped = true;
    }
    if (swapped) { layout(); renderMini(); }
    var box = list.getBoundingClientRect();
    var y = e.clientY;
    if (y < box.top + 40 * scale) list.scrollTop -= 10;
    else if (y > box.bottom - 40 * scale) list.scrollTop += 10;
  }

  // ---- mini player: swipe to switch station (StationCarouselState) ----
  var miniMoved = false;
  (function () {
    var sx = 0, dx = 0, pid = null, dragging = false;
    miniClip.addEventListener('pointerdown', function (e) {
      if (e.button !== 0 || e.target.closest('.fc-mini-play')) return;
      sx = e.clientX / scale; dx = 0; pid = e.pointerId; dragging = false;
    });
    miniClip.addEventListener('pointermove', function (e) {
      if (pid !== e.pointerId) return;
      dx = e.clientX / scale - sx;
      if (!dragging && Math.abs(dx) > SLOP) {
        dragging = true;
        try { miniClip.setPointerCapture(pid); } catch (err) { /* ignore */ }
        miniTrack.style.transition = 'none';
      }
      if (dragging) {
        var cur = byId(currentId), idx = stations.indexOf(cur);
        var lo = idx < stations.length - 1 ? -(MINI_W + MINI_GAP) : 0;   // no next → can't drag left
        var hi = idx > 0 ? (MINI_W + MINI_GAP) : 0;
        var d = Math.max(lo, Math.min(hi, dx));
        miniTrack.style.transform = 'translateX(' + (-(MINI_W + MINI_GAP) + d) + 'px)';
      }
    });
    function up(e) {
      if (pid !== e.pointerId) return;
      pid = null;
      if (!dragging) return;
      dragging = false;
      miniMoved = true; setTimeout(function () { miniMoved = false; }, 0);
      var cur = byId(currentId), idx = stations.indexOf(cur);
      var target = null, dir = 0;
      if (dx < -MINI_W / 3 && idx < stations.length - 1) { target = stations[idx + 1]; dir = -1; }
      else if (dx > MINI_W / 3 && idx > 0) { target = stations[idx - 1]; dir = 1; }
      miniTrack.style.transition = 'transform 0.25s ease';
      miniTrack.style.transform = 'translateX(' + (-(MINI_W + MINI_GAP) + dir * (MINI_W + MINI_GAP)) + 'px)';
      if (target) setTimeout(function () { start(target.id); }, 250);
    }
    miniClip.addEventListener('pointerup', up);
    miniClip.addEventListener('pointercancel', up);
  })();

  // ====================================================================================
  // Overlay screens (Discover, Settings): slide in over the library like a new Activity
  // ====================================================================================
  var popups = [];
  function closePopups() { popups.forEach(function (p) { p.hidden = true; }); }
  document.addEventListener('click', function (e) {
    popups.forEach(function (p) { if (!p.hidden && !p.contains(e.target) && !p._anchor.contains(e.target)) p.hidden = true; });
  });

  function setUnderInert(on) {
    [main, bottom].forEach(function (e) { if (on) e.setAttribute('inert', ''); else e.removeAttribute('inert'); });
  }

  function makeOverlay(title, barBg) {
    var ov = el('div', 'fc-ov');
    var bar = el('div', 'fc-ov-bar');
    bar.style.background = barBg;
    bar.appendChild(makeStatusBar());
    var tb = el('div', 'fc-ov-toolbar');
    var back = el('button', 'fc-ov-back', I.back);
    back.setAttribute('aria-label', STR.back);
    tb.appendChild(back);
    tb.appendChild(txt('span', 'fc-ov-title', title));
    bar.appendChild(tb);
    var body = el('div', 'fc-ov-body');
    ov.appendChild(bar);
    ov.appendChild(body);
    screen.appendChild(ov);
    var api = {
      el: ov, body: body, isOpen: false, onOpen: null,
      open: function () { api.isOpen = true; ov.classList.add('fc-open'); setUnderInert(true); if (api.onOpen) api.onOpen(); },
      close: function () { api.isOpen = false; ov.classList.remove('fc-open'); setUnderInert(false); closePopups(); layout(); render(); }
    };
    back.addEventListener('click', api.close);
    return api;
  }

  // M3 AlertDialog
  function showDialog(title, text, confirmLabel, cancelLabel, onConfirm, onCancel) {
    var scrim = el('div', 'fc-scrim');
    var d = el('div', 'fc-dialog');
    d.setAttribute('role', 'alertdialog');
    d.appendChild(txt('div', 'fc-dialog-title', title));
    d.appendChild(txt('div', 'fc-dialog-text', text));
    var btns = el('div', 'fc-dialog-btns');
    var bc = txt('button', null, cancelLabel), bo = txt('button', null, confirmLabel);
    btns.appendChild(bc); btns.appendChild(bo);
    d.appendChild(btns);
    scrim.appendChild(d);
    screen.appendChild(scrim);
    function done(cb) { document.removeEventListener('keydown', onKey); scrim.remove(); if (cb) cb(); }
    function onKey(e) { if (e.key === 'Escape') done(onCancel); }
    document.addEventListener('keydown', onKey);
    bo.focus();
    bc.addEventListener('click', function () { done(onCancel); });
    bo.addEventListener('click', function () { done(onConfirm); });
    scrim.addEventListener('click', function (e) { if (e.target === scrim) done(onCancel); });
  }

  // ---- Discover: Radio Browser client (RadioBrowserApi) ----
  var API = 'https://all.api.radio-browser.info/json/';
  var cache = {};
  var CACHE_TTL = 5 * 60 * 1000;

  function qs(params) {
    return Object.keys(params).map(function (k) { return encodeURIComponent(k) + '=' + encodeURIComponent(params[k]); }).join('&');
  }
  function getJson(url, attempt) {
    var ctl = 'AbortController' in window ? new AbortController() : null;
    var timer = setTimeout(function () { if (ctl) ctl.abort(); }, 8000);
    return fetch(url, ctl ? { signal: ctl.signal } : undefined).then(function (res) {
      clearTimeout(timer);
      if (!res.ok) throw new Error('HTTP ' + res.status);
      return res.json();
    }).catch(function (err) {
      clearTimeout(timer);
      if (attempt < 1) return getJson(url, attempt + 1);   // the round-robin host may hand back a different mirror
      throw err;
    });
  }
  function parseStations(arr) {
    var out = [];
    (arr || []).forEach(function (o) {
      var name = String(o.name || '').trim();
      var url = String(o.url_resolved || o.url || '').trim();
      if (!name || !url) return;
      out.push({
        uuid: o.stationuuid || '', name: name, url: url, tags: String(o.tags || ''), bitrate: o.bitrate | 0,
        hls: o.hls === 1, countryCode: o.countrycode || '', codec: o.codec || '', votes: o.votes | 0,
        homepage: o.homepage || '', favicon: o.favicon || '',
        distanceKm: typeof o.distance === 'number' ? o.distance / 1000 : null, sslError: o.ssl_error === 1
      });
    });
    return out;
  }
  function fetchStations(params) {
    var url = API + 'stations/search?' + qs(params);
    var hit = cache[url];
    if (hit && hit.exp > Date.now()) return Promise.resolve(hit.v);
    return getJson(url, 0).then(function (arr) {
      var v = parseStations(arr);
      cache[url] = { v: v, exp: Date.now() + CACHE_TTL };
      return v;
    });
  }
  function searchBy(kind, q) {
    var p = { limit: 30, hidebroken: 'true', order: 'votes', reverse: 'true' };
    p[kind] = q;
    return fetchStations(p);
  }
  function topStations() { return fetchStations({ limit: 30, hidebroken: 'true', order: 'votes', reverse: 'true' }); }
  function searchNearby(lat, lon) {
    return fetchStations({ geo_lat: lat, geo_long: lon, geo_distance: 50000, limit: 30, hidebroken: 'true', order: 'distance' });
  }
  // The app filters a locally cached copy of /json/tags by prefix; the demo asks the API instead
  function suggestTags(prefix) {
    var p = prefix.toLowerCase();
    return getJson(API + 'tags/' + encodeURIComponent(p) + '?' + qs({ limit: 100, order: 'stationcount', reverse: 'true', hidebroken: 'true' }), 0)
      .then(function (arr) {
        return (arr || []).map(function (t) { return String(t.name || ''); })
          .filter(function (n) { return n.toLowerCase().indexOf(p) === 0; }).slice(0, 8);
      });
  }

  function flagEmoji(code) {
    if (!/^[A-Za-z]{2}$/.test(code || '')) return null;
    var u = code.toUpperCase();
    return String.fromCodePoint(0x1F1E6 + u.charCodeAt(0) - 65, 0x1F1E6 + u.charCodeAt(1) - 65);
  }
  function formatVotes(v) {
    if (v >= 1000000) return (v / 1000000).toFixed(1) + 'M';
    if (v >= 1000) return (v / 1000).toFixed(1) + 'k';
    return String(v);
  }
  function stationSubtitle(st) {
    var parts = [];
    if (st.distanceKm != null) parts.push('📍 ' + st.distanceKm.toFixed(1) + ' km');
    var flag = flagEmoji(st.countryCode);
    if (flag) parts.push(flag);
    if (st.tags) parts.push(st.tags);
    var br = [st.bitrate > 0 ? st.bitrate + ' kbps' : '', st.codec ? st.codec.toUpperCase() : ''].filter(Boolean).join(' ');
    if (br) parts.push(br);
    return parts.length ? parts.join(' • ') : st.url;
  }
  function displayRegion(code, loc) {
    try {
      var n = new Intl.DisplayNames([loc], { type: 'region' }).of(code);
      return n && n !== code ? n : null;
    } catch (e) { return null; }
  }
  // DiscoverStationsViewModel.resolveDefaultBrowseRegionCode(): the RU page browses Russia,
  // otherwise the browser's own region (en-US → US); no region → global top stations
  function resolveRegion() {
    if (lang === 'ru') return 'RU';
    var l = (navigator.languages && navigator.languages[0]) || navigator.language || '';
    var m = /[-_]([A-Za-z]{2})$/.exec(l);
    return m ? m[1].toUpperCase() : null;
  }

  // ---- Discover screen ----
  var dz = makeOverlay(STR.discover, 'rgba(41,30,23,0.9)');
  var D = { mode: 'name', query: '', results: [], searching: false, hasSearched: false, error: null, browse: [], browseRegion: null,
            loadingBrowse: false, browseLoaded: false, denied: false, genre: null, tags: [], added: {} };
  var reqId = 0, debounceTimer = null, suggestTimer = null, suggestId = 0;

  dz.body.classList.add('fc-dz-body');
  var chipRow = el('div', 'fc-dchips');
  var modeChips = {};
  [['name', STR.modeName, null], ['nearby', STR.modeNearby, I.pin], ['genre', STR.modeGenre, null], ['country', STR.modeCountry, null]].forEach(function (m) {
    var b = el('button', 'fc-fchip' + (m[2] ? ' fc-fchip-icon' : ''), (m[2] || '') + '<span>' + esc(m[1]) + '</span>');
    b.addEventListener('click', function () { if (m[0] === 'nearby') { onMode('nearby'); requestNearby(); } else onMode(m[0]); });
    chipRow.appendChild(b);
    modeChips[m[0]] = b;
  });
  dz.body.appendChild(chipRow);

  var dField = el('div', 'fc-dfield');
  var dFieldBox = el('div', 'fc-search-field');
  var dInput = document.createElement('input');
  dInput.type = 'text'; dInput.autocomplete = 'off'; dInput.spellcheck = false;
  dFieldBox.appendChild(dInput);
  dField.appendChild(dFieldBox);
  dz.body.appendChild(dField);

  var dExtra = el('div', 'fc-dextra');        // genre chips / tag suggestions / country flags / permission hint
  dz.body.appendChild(dExtra);
  var dResults = el('div', 'fc-dresults');
  dz.body.appendChild(dResults);

  dz.onOpen = function () {
    D.added = {};
    renderDiscover();
    if (!D.browseLoaded) loadBrowse();
  };

  dInput.addEventListener('input', function () {
    D.query = dInput.value;
    D.genre = null;
    if (D.mode === 'genre') updateTags(D.query);
    scheduleSearch();
    renderDiscover();
  });

  function onMode(mode) {
    if (mode === D.mode) return;
    reqId++; clearTimeout(debounceTimer); clearTimeout(suggestTimer); suggestId++;
    D.mode = mode; D.query = ''; D.results = []; D.searching = false; D.hasSearched = false;
    D.error = null; D.denied = false; D.genre = null; D.tags = [];
    dInput.value = '';
    renderDiscover();
  }

  function scheduleSearch() {
    var q = D.query.trim();
    if (!q) {
      reqId++; clearTimeout(debounceTimer);
      D.results = []; D.searching = false; D.hasSearched = false; D.error = null;
      return;
    }
    runSearch(q, 400);
  }

  function runSearch(q, debounce) {
    var kind = { name: 'name', genre: 'tag', country: 'country' }[D.mode];
    if (!kind) return;
    var id = ++reqId;
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(function () {
      if (id !== reqId) return;
      D.searching = true; D.error = null; renderDiscover();
      searchBy(kind, q).then(function (res) {
        if (id !== reqId) return;
        D.results = res; D.searching = false; D.hasSearched = true; renderDiscover();
      }, function () {
        if (id !== reqId) return;
        D.results = []; D.searching = false; D.hasSearched = true; D.error = STR.searchError; renderDiscover();
      });
    }, debounce);
  }

  function pickGenre(tag) {
    clearTimeout(suggestTimer); suggestId++;
    D.genre = tag; D.tags = [];
    runSearch(tag, 0);
    renderDiscover();
  }

  function updateTags(prefix) {
    clearTimeout(suggestTimer);
    var p = prefix.trim();
    var id = ++suggestId;
    if (!p) { D.tags = []; return; }
    suggestTimer = setTimeout(function () {
      suggestTags(p).then(function (tags) {
        if (id !== suggestId || D.mode !== 'genre') return;
        D.tags = tags; renderDiscover();
      }, function () { /* suggestions are best-effort */ });
    }, 200);
  }

  function loadBrowse() {
    D.loadingBrowse = true; renderDiscover();
    var code = resolveRegion();
    var countryName = code ? displayRegion(code, 'en') : null;
    var region = null;
    var p = countryName ? searchBy('country', countryName) : Promise.resolve([]);
    p.then(function (res) {
      if (res.length) { region = code; return res; }
      return topStations();
    }).catch(function () { return []; }).then(function (res) {
      D.browse = res; D.browseRegion = region; D.loadingBrowse = false; D.browseLoaded = res.length > 0; renderDiscover();
    });
  }

  // NEARBY: rationale dialog → browser geolocation prompt → search around the fix (50 km)
  function requestNearby() {
    if (navigator.permissions && navigator.permissions.query) {
      navigator.permissions.query({ name: 'geolocation' }).then(function (p) {
        if (p.state === 'granted') doNearby(); else askRationale();
      }, askRationale);
    } else askRationale();
  }
  function askRationale() {
    showDialog(STR.locTitle, STR.locText, STR.locContinue, STR.cancel, doNearby, function () {
      D.denied = true; renderDiscover();
    });
  }
  function doNearby() {
    var id = ++reqId;
    D.searching = true; D.error = null; D.denied = false; renderDiscover();
    if (!navigator.geolocation) { D.searching = false; D.hasSearched = true; D.error = STR.locUnavailable; renderDiscover(); return; }
    navigator.geolocation.getCurrentPosition(function (pos) {
      searchNearby(pos.coords.latitude, pos.coords.longitude).then(function (res) {
        if (id !== reqId) return;
        D.results = res; D.searching = false; D.hasSearched = true; renderDiscover();
      }, function () {
        if (id !== reqId) return;
        D.results = []; D.searching = false; D.hasSearched = true; D.error = STR.searchError; renderDiscover();
      });
    }, function (err) {
      if (id !== reqId) return;
      D.searching = false;
      if (err && err.code === 1) { D.denied = true; } else { D.hasSearched = true; D.error = STR.locUnavailable; }
      renderDiscover();
    }, { timeout: 20000, maximumAge: 600000 });
  }

  function addFromDirectory(st) {
    if (D.added[st.url]) return;
    insertStation({ name: st.name, url: st.url, desc: st.tags, hls: st.hls, favicon: st.favicon });
    D.added[st.url] = true;
    renderDiscover();
  }

  function fchip(label, selected, onClick, cls) {
    var b = txt('button', 'fc-fchip' + (selected ? ' fc-sel' : '') + (cls ? ' ' + cls : ''), label);
    b.addEventListener('click', onClick);
    return b;
  }

  function resultCard(st) {
    var card = el('div', 'fc-rb');
    var topRow = el('div', 'fc-rb-top');
    topRow.appendChild(txt('span', 'fc-rb-emoji', '📻'));
    topRow.appendChild(txt('span', 'fc-rb-name', st.name));
    var isAdded = !!D.added[st.url] || !!byUrl(st.url);
    if (isAdded) {
      var ck = el('span', 'fc-rb-check', I.check);
      ck.setAttribute('role', 'img'); ck.setAttribute('aria-label', STR.added);
      topRow.appendChild(ck);
    } else {
      var ab = txt('button', 'fc-rb-add', STR.add);
      ab.addEventListener('click', function () { addFromDirectory(st); });
      topRow.appendChild(ab);
    }
    var sub = el('div', 'fc-rb-sub');
    sub.appendChild(txt('span', 'fc-rb-subtext', stationSubtitle(st)));
    if (st.votes > 0) {
      var v = el('span', 'fc-rb-votes', I.star);
      v.appendChild(txt('span', null, formatVotes(st.votes)));
      sub.appendChild(v);
    }
    if (st.sslError) {
      var w = el('span', 'fc-rb-warn', I.warn);
      w.title = STR.sslWarn; w.setAttribute('role', 'img'); w.setAttribute('aria-label', STR.sslWarn);
      sub.appendChild(w);
    }
    if (isHttp(st.homepage)) {
      var a = el('a', 'fc-rb-open', I.open);
      a.href = st.homepage; a.target = '_blank'; a.rel = 'noopener noreferrer'; a.setAttribute('aria-label', STR.visit);
      sub.appendChild(a);
    }
    card.appendChild(topRow); card.appendChild(sub);
    return card;
  }

  function spinner() {
    return el('div', 'fc-spin', '<svg viewBox="0 0 48 48"><circle cx="24" cy="24" r="20" fill="none" stroke="currentColor" stroke-width="4" stroke-linecap="round" stroke-dasharray="90 40"/></svg>');
  }

  function renderDiscover() {
    Object.keys(modeChips).forEach(function (k) { modeChips[k].classList.toggle('fc-sel', D.mode === k); });
    var textMode = D.mode === 'name' || D.mode === 'genre';
    dField.style.display = textMode ? '' : 'none';
    dInput.placeholder = D.mode === 'genre' ? STR.hintGenre : STR.hintName;
    dInput.setAttribute('aria-label', dInput.placeholder);

    dExtra.innerHTML = '';
    if (D.mode === 'genre') {
      if (D.tags.length) {
        var sug = el('div', 'fc-tags');
        D.tags.forEach(function (t) {
          var b = txt('button', 'fc-tag', t);
          b.addEventListener('click', function () { D.query = t; dInput.value = t; pickGenre(t); });
          sug.appendChild(b);
        });
        dExtra.appendChild(sug);
      } else {
        var gr = el('div', 'fc-hrow');
        GENRES.forEach(function (g) { gr.appendChild(fchip(STR.genres[g], D.genre === g, function () { pickGenre(g); })); });
        dExtra.appendChild(gr);
      }
    } else if (D.mode === 'country') {
      var half = Math.ceil(COUNTRIES.length / 2);
      var box = el('div', 'fc-flags');
      [COUNTRIES.slice(0, half), COUNTRIES.slice(half)].forEach(function (group) {
        var r = el('div', 'fc-hrow');
        group.forEach(function (c) {
          var b = fchip(flagEmoji(c[0]) || c[0], D.query === c[1], function () {
            D.query = c[1]; scheduleSearch(); renderDiscover();
          }, 'fc-flag');
          b.setAttribute('aria-label', c[1]);
          r.appendChild(b);
        });
        box.appendChild(r);
      });
      dExtra.appendChild(box);
    } else if (D.mode === 'nearby' && D.denied) {
      dExtra.appendChild(txt('div', 'fc-hint', STR.locDenied));
    }

    var keep = dResults.scrollTop;
    dResults.innerHTML = '';
    dResults.classList.remove('fc-centered');
    function message(t) { dResults.classList.add('fc-centered'); dResults.appendChild(txt('div', 'fc-dmsg', t)); }
    function center(node) { dResults.classList.add('fc-centered'); dResults.appendChild(node); }
    function listOf(items, header) {
      var inr = el('div', 'fc-rlist');
      if (header) inr.appendChild(txt('div', 'fc-rheader', header));
      items.forEach(function (st) { inr.appendChild(resultCard(st)); });
      dResults.appendChild(inr);
    }
    if (D.searching) center(spinner());
    else if (D.error) message(D.error);
    else if (!D.hasSearched) {
      if (D.browse.length) {
        var header = STR.browseGlobal;
        if (D.browseRegion) {
          var nm = displayRegion(D.browseRegion, lang);
          if (nm) header = fmt(STR.browseCountry, nm);
        }
        listOf(D.browse, header);
      } else if (D.loadingBrowse) center(spinner());
      else message(STR.prompt);
    } else if (!D.results.length) message(STR.empty);
    else listOf(D.results);
    dResults.scrollTop = keep;
  }

  chip.addEventListener('click', function () { markInteracted(); dz.open(); });

  // ---- Settings screen (UI only; the switch and pickers change their own value) ----
  var sz = makeOverlay(STR.settings, '#241a15');
  sz.body.classList.add('fc-sz-body');

  function section(label) { return txt('div', 'fc-slabel', label); }
  function panel() { return el('div', 'fc-spanel'); }
  function divider() { return el('div', 'fc-sdiv'); }
  function sRow(icon, title, trailing, onClick) {
    var wrap = el('div', 'fc-srow-wrap');
    var r = el(onClick ? 'button' : 'div', 'fc-srow');
    r.appendChild(el('span', 'fc-sbadge', icon));
    r.appendChild(txt('span', 'fc-stitle', title));
    var trail = el('span', 'fc-strail');
    if (trailing) trail.appendChild(trailing);
    r.appendChild(trail);
    if (onClick) r.addEventListener('click', function (e) { e.stopPropagation(); onClick(); });
    wrap.appendChild(r);
    return wrap;
  }
  function valueTrail(valueEl) {
    var t = el('span', 'fc-vtrail');
    t.appendChild(valueEl);
    t.appendChild(el('span', 'fc-sdrop', I.drop));
    return t;
  }
  // DropdownMenu anchored under a settings row
  function dropdown(wrap, labels, onPick) {
    var m = el('div', 'fc-menu fc-menu-row');
    m.hidden = true; m._anchor = wrap; m.setAttribute('role', 'menu');
    labels.forEach(function (label, i) {
      var b = txt('button', null, label);
      b.setAttribute('role', 'menuitem');
      b.addEventListener('click', function (e) { e.stopPropagation(); m.hidden = true; onPick(i); });
      m.appendChild(b);
    });
    wrap.appendChild(m);
    popups.push(m);
    return function toggle() { var was = m.hidden; closePopups(); m.hidden = !was; };
  }

  function buildSettings() {
    var b = sz.body;
    b.appendChild(section(STR.sGeneral));
    var p1 = panel();

    var langVal = txt('span', 'fc-sval', STR.sSystem);
    var langRow = sRow(I.globe, STR.sLanguage, valueTrail(langVal), function () { toggleLang(); });
    var toggleLang = dropdown(langRow, LANGS, function (i) { langVal.textContent = LANGS[i]; });
    p1.appendChild(langRow); p1.appendChild(divider());

    var sw = el('span', 'fc-switch fc-on');
    sw.setAttribute('aria-hidden', 'true');
    sw.appendChild(el('span', 'fc-switch-thumb'));
    var swRow = sRow(I.cell, STR.sMetered, sw, function () {
      var on = !sw.classList.contains('fc-on');
      sw.classList.toggle('fc-on', on); swBtn.setAttribute('aria-checked', String(on));
    });
    var swBtn = swRow.firstChild;
    swBtn.setAttribute('role', 'switch'); swBtn.setAttribute('aria-checked', 'true');
    p1.appendChild(swRow);
    p1.appendChild(divider());

    var bufVal = txt('span', 'fc-sval', fmt(STR.sBufShort, 30));
    var bufRow = sRow(I.history, STR.sBuffer, valueTrail(bufVal), function () { toggleBuf(); });
    var toggleBuf = dropdown(bufRow, BUFFERS.map(function (x) { return fmt(STR.sBufValue, x[0], x[1]); }),
      function (i) { bufVal.textContent = fmt(STR.sBufShort, BUFFERS[i][0]); });
    p1.appendChild(bufRow);
    b.appendChild(p1);

    b.appendChild(section(STR.sBackup));
    var p2 = panel();
    p2.appendChild(sRow(I.up, STR.sExport, null, notice)); p2.appendChild(divider());
    p2.appendChild(sRow(I.down, STR.sImport, null, notice)); p2.appendChild(divider());
    p2.appendChild(sRow(I.history, STR.sRestore, null, restoreCurated));
    b.appendChild(p2);

    b.appendChild(section(STR.sSupport));
    var p3 = panel();
    p3.appendChild(sRow(I.battery, STR.sBattery, null, notice)); p3.appendChild(divider());
    p3.appendChild(sRow(I.bug, STR.sGithub, null, notice)); p3.appendChild(divider());
    p3.appendChild(sRow(I.feedback, STR.sEmail, null, notice));
    b.appendChild(p3);

    b.appendChild(txt('div', 'fc-sfooter', fmt(STR.sVersion, APP_VERSION)));
  }
  buildSettings();

  // SettingsViewModel.restoreCuratedStations(): re-insert pack entries missing by url
  function restoreCurated() {
    var n = 0;
    curatedStations.forEach(function (s) {
      if (byUrl(s.url) || nameTaken(s.name)) return;      // the pack skips a name clash rather than renaming
      var r = rows[s.id];
      stations.push(s);
      r.removed = false;
      r.el.classList.remove('fc-removing', 'fc-gone');
      setOffset(r, 0);
      n++;
    });
    layout(); render();
    showToast(fmt(STR.sRestored, n));
  }
  function openSettings() { markInteracted(); sz.open(); }

  // ---- first-visit cues: swipe hint on the first station, then a tap nudge on its play button ----
  var tip = null;
  function markInteracted() {
    if (interacted) return;
    interacted = true;
    device.classList.remove('fc-idle');
    stopNudge();
  }
  screen.addEventListener('pointerdown', markInteracted, true);

  function startNudge() {
    if (interacted) return;
    var r = rows[curatedStations[0].id];
    if (!r || r.removed) return;
    r.el.classList.add('fc-nudge');
    tip = txt('div', 'fc-tip', STR.tap);
    inner.appendChild(tip);
  }
  function stopNudge() {
    Object.keys(rows).forEach(function (k) { rows[k].el.classList.remove('fc-nudge'); });
    if (tip) { tip.remove(); tip = null; }
  }

  function cancelHint() {
    if (hintDone) return;
    hintCancelled = true;
    var r = rows[curatedStations[0].id];
    r.el.classList.remove('fc-hint');
    if (r.off) setOffset(r, 0, true);
  }
  function playHint() {
    if (hintDone) return;
    hintDone = true;
    var r = rows[curatedStations[0].id];
    var steps = [
      [900, function () { r.el.classList.add('fc-hint'); }],
      [300, function () { setOffset(r, -REVEAL, true); }],
      [1600, function () { setOffset(r, 0, true); }],
      [400, function () { r.el.classList.remove('fc-hint'); }],
      [500, function () { startNudge(); }]
    ];
    (function run(i) {
      if (hintCancelled || i >= steps.length) return;
      setTimeout(function () { if (hintCancelled) return; steps[i][1](); run(i + 1); }, steps[i][0]);
    })(0);
  }
  if ('IntersectionObserver' in window && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    var io = new IntersectionObserver(function (entries) {
      if (entries[0].isIntersecting) { io.disconnect(); playHint(); }
    }, { threshold: 0.5 });
    io.observe(stage);
  } else {
    setTimeout(startNudge, 800);
  }

  // ---- scaling: authored at 1dp = 1px, fitted to the column width ----
  function fit() {
    var w = stage.clientWidth || DEV_W;
    scale = w / DEV_W;
    device.style.transform = 'scale(' + scale + ')';
    stage.style.height = Math.round(DEV_H * scale) + 'px';
  }
  if ('ResizeObserver' in window) new ResizeObserver(fit).observe(stage);
  window.addEventListener('resize', fit);
  fit();

  layout();
  render();
})();
