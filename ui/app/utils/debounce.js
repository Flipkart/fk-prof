export default function debounce (func, wait) {
  let timeout;
  return function () {
    const _this = this;
    const args = arguments;
    const later = function () {
      timeout = null;
      func.apply(_this, args);
    };
    clearTimeout(timeout);
    timeout = setTimeout(later, wait);
  };
}
