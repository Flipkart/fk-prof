export default function findIndex (list = [], prop, value) {
  return list.reduce((prev, currentItem, index) => {
    if (currentItem[prop] === value) {
      return index;
    }
    return prev;
  }, -1);
}
