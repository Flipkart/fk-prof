function pad (number) {
  if (typeof number !== 'string') number = `${number}`;
  if (number.length === 1) number = `0${number}`;
  return number;
}

function getProfileListFormat (date) {
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

// takes in date object and view
export default function dateFormat (date, view) {
  return {
    profileList: getProfileListFormat(date),
  }[view];
}
