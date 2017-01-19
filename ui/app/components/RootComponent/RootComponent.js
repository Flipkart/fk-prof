import React, { Component } from 'react';
import { withRouter } from 'react-router';

import Header from 'components/HeaderComponent';
import AppIdSelector from 'components/AppIdSelectorComponent';

import 'components/RootComponent/RootComponent.css';

const BaseComponent = Komponent => class extends Component {
  componentDidUpdate () {
      componentHandler.upgradeDom(); // eslint-disable-line
  }

  render () {
    return <Komponent {...this.props} />;
  }
};

const RootComponent = props => {
  const updateQueryParams = ({ pathname = '/', query }) => props.router.push({ pathname, query });
  const updateAppIdQueryParam = o => updateQueryParams({ query: { appId: o.name } });

  const selectedAppId = props.location.query.appId;
  const selectedClusterId = props.location.query.clusterId;

  return (
    <div>
      <Header />
      <main style={{ paddingTop: 64 }}>
        <div className="page-content">
          <div className="mdl-grid">
            <div className="mdl-cell mdl-cell--3-col">
              <AppIdSelector
                onChange={updateAppIdQueryParam}
                value={selectedAppId}
              />
            </div>
            <div className="mdl-cell mdl-cell--3-col">
              {selectedAppId && <h3>Cluster Id here</h3>}
            </div>
            <div className="mdl-cell mdl-cell--3-col">
              
            </div>
            <div className="mdl-cell mdl-cell--3-col">
              
            </div>
          </div>
          { props.children }
        </div>
      </main>
    </div>
  );
};

RootComponent.propTypes = {
  children: React.PropTypes.node,
};

export default BaseComponent(withRouter(RootComponent));
