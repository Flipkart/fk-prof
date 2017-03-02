const prefixes = ['Webkit', 'Moz', 'O', 'ms', ''];

function capitalize (str = '') {
  return str.charAt(0).toUpperCase() + str.slice(1);
}

export default function prefixUtil (propertyMap) {
  const prefixed = {};
  for (const property in propertyMap) {
    if (propertyMap.hasOwnProperty(property)) {
      const val = propertyMap[property];
      prefixes.forEach((prefix) => {
        prefixed[prefix + (prefix ? capitalize(property) : property)] = val;
      });
    }
  }
  return prefixed;
}
