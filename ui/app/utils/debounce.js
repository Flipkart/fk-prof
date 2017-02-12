export default function debounce (func, wait) {
  let timeout;
  return function () {
    const _this = this;
    const args = arguments;

    // if event is a React SyntheticEvent, call persist()
    // https://facebook.github.io/react/docs/events.html#event-pooling
    if (args[0] && args[0].persist && typeof args[0].persist === 'function') {
      args[0].persist();
    }

    const later = function () {
      timeout = null;
      func.apply(_this, args);
    };
    clearTimeout(timeout);
    timeout = setTimeout(later, wait);
  };
}
