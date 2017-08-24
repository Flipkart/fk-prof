import http from 'utils/http';

export default class HotMethodStore {
  constructor(url) {
    this.nodes = {};
    this.methodLookup = {};
    this.url = url;
  }

  //nodes at bodyRoot level are guaranteed to have their children in the response
  flatten(root, bodyRoot, topLevel, sampleCount) {
    const dedupedNodes = Object.entries(root).reduce((accumNodes, [k, v]) => {
      if (k !== 'method_lookup') {
        let key = v['data'][0];      //method id
        if (!topLevel) {
          key = key + ':' + v['data'][1];       //add line number if this layer is top layer of hot-method tree
        }
        if (accumNodes[key]) {
          accumNodes[key] = [v['data'], undefined, accumNodes[key][2].push([k, v['data'][2]])];       //[data, chld, parts]
          accumNodes[key][0][2] = accumNodes[key][0][2] + v['data'][2];       //adding up the sample count
        } else {
          accumNodes[key] = [v['data'], undefined, [k, v['data'][2]]];        //[data, chld, parts]
        }
      }
      return accumNodes;
    }, {});

    const curLayerIds = [];
    Object.entries(dedupedNodes).forEach(([k, v]) => {
      this.nodes.push(v);                                     //actual update in the nodes list
      curLayerIds.push(this.nodes.length - 1);
      v[2].forEach(([part, sampleCount]) => {
        if (root[part]['chld']) {
          const nextLayerIds = this.flatten(root[part]['chld'], false, false);
          v[1] = v[1] ? v[1].concat(nextLayerIds) : nextLayerIds;
        }
      });
      if (!v[1] && bodyRoot) {        //if no part has a child field and if this is the body's root then it is a leaf node
        v[1] = [];
      }
      if (v[1] && v.length === 3) {
        v.pop();        //if chld is populated then remove parts
      }
    });
    return curLayerIds;
  }

  getNameWithArgs(uniqueId) {
    return uniqueId + ' ' + this.methodLookup[this.nodes[uniqueId][0][0]] + ' ' + this.nodes[uniqueId][0][1];
  }

  getChildrenAsync(uniqueId) {
    if (uniqueId >= this.nodes.length) { // never supposed to be true
      return [];
    }
    if (this.nodes[uniqueId] && this.nodes[uniqueId][1]) {
      return Promise.resolve(this.nodes[uniqueId][1]);
    }
    const body = (uniqueId === -1) ? [] : this.nodes[uniqueId][2];
    return http.post(this.url, body).then(
      resp => {
        console.log('body: ', body, 'response: ', resp);
        if (uniqueId === -1) {
          this.nodes[uniqueId] = [null, undefined, Object.keys(resp).filter((k) => k !== 'method_lookup').map(k => parseInt(k))];
        }
        this.nodes[uniqueId][1] = this.flatten(resp, true, uniqueId === -1);
        if (this.nodes[uniqueId][1] && this.nodes[uniqueId].length === 3) {
          this.nodes[uniqueId].pop();        //if chld is populated then remove parts
        }

        Object.assign(this.methodLookup, resp['method_lookup']);

        return this.nodes[uniqueId][1];
      }
    );
  }

  getSampleCount(uniqueId) {
    return this.nodes[uniqueId][0][2];
  }

  getChildren(uniqueId) {
    if (this.nodes[uniqueId]) {
      return this.nodes[uniqueId][1];
    } else {
      return [];
    }
  }
}
