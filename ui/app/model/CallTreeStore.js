import postWithRetryOnAccept from '../utils/httpHelper';

export default class CallTreeStore {
  constructor(url) {
    this.nodes = {};
    this.methodLookup = {};
    this.url = url;
  }

  flatten(respRoot, bodyRoot, parent) {
    //sorting logic on Object.entries if any
    Object.entries(respRoot).forEach(([k, v]) => {
      if (k !== 'method_lookup') {
        this.nodes[parseInt(k)] = [v['data'], v['chld'] ? Object.keys(v['chld']).map(c => parseInt(c)) : (bodyRoot ? [] : undefined), parent];    //nodes at bodyRoot level are guaranteed to have their children in the response
        if (v['chld']) {
          this.flatten(v['chld'], false, parseInt(k));
        }
      }
    });
  }

  handleResponse(resp, uniqueId) {
    this.flatten(resp, true, uniqueId === -1? uniqueId: this.nodes[uniqueId][2]);
    Object.entries(resp['method_lookup']).forEach(([k,v])=>{
      const splits = v.split(" ");
      if (splits.length === 2) {
        resp['method_lookup'][k] = splits;
      } else {
        resp['method_lookup'][k] = [v, ""];
      }
    });
    Object.assign(this.methodLookup, resp['method_lookup']);
    if (uniqueId === -1) {
      const rootKey = Object.keys(resp).filter(k => k !== 'method_lookup')[0]; // array should be of size 1
      const rootChld = resp[rootKey]['chld'];
      delete resp[rootKey];
      Object.assign(resp, rootChld);
      this.nodes[uniqueId] = [null, Object.keys(resp).filter((k) => k !== 'method_lookup').map(k => parseInt(k)), undefined];
    }
    return this.nodes[uniqueId][1];
  }

  getChildrenAsync(uniqueId) {
    if (uniqueId >= this.nodes.length) { // never supposed to be true
      return [];
    }
    if (this.nodes[uniqueId] && this.nodes[uniqueId][1]) {
      return Promise.resolve(this.nodes[uniqueId][1]);
    }
    const body = (uniqueId === -1) ? [] : [uniqueId];
    return postWithRetryOnAccept(this.url, body, 5).then((resp) => this.handleResponse(resp, uniqueId), (err) => Promise.reject((err.response && (err.response.message || err.response.error)) || err));
  }

  getMethodName(uniqueId, showLineNo) {
    return this.methodLookup[this.nodes[uniqueId][0][0]][0] + ((showLineNo) ? ': ' + this.nodes[uniqueId][0][1] : '');
  }

  getFullyQualifiedMethodName(uniqueId, showLineNo) {
    return this.methodLookup[this.nodes[uniqueId][0][0]].join(' ') + ((showLineNo) ? ': ' + this.nodes[uniqueId][0][1] : '');
  }

  getSampleCount(uniqueId) {
    return this.nodes[uniqueId][0][2];
  }

  getChildren(uniqueId) {
    if (this.nodes[uniqueId] && this.nodes[uniqueId][1]) {
      return this.nodes[uniqueId][1];
    } else {
      return [];
    }
  }

  getParent(uniqueId) {
    return this.nodes[uniqueId][2];
  }

  isRoot(uniqueId) {
    return uniqueId === 0;
  }
}
