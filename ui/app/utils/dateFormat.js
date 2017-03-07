// takes in date object and view
export default function dateFormat (date, view) {
  return {
    profileList: getProfileListFormat(date),
  }[view];
}

function pad (number) {
  if (typeof number !== 'string') number = `${number}`;
  if (number.length === 1) number = `0${number}`;
  return number;
}

function adjustFor24 (date) {
  if (date.getHours() > 12) return date.getHours() % 12;
  return date.getHours();
}

function getProfileListFormat (date) {
  const start = date.getHours();
  const end = new Date(new Date().setHours(start + 1));
  return `${pad(adjustFor24(date))} - ${pad(adjustFor24(end))} ${start >= 12 ? 'PM' : 'AM'}`;
}
