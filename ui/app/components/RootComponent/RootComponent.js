import React, { Component } from 'react';

import Header from 'components/HeaderComponent';

const BaseComponent = Komponent => class extends Component {
  componentDidUpdate () {
    componentHandler.upgradeDom(); // eslint-disable-line
  }

  render () {
    return <Komponent {...this.props} />;
  }
};

const RootComponent = props => (
  <div className="mdl-layout mdl-js-layout mdl-layout--fixed-header">
    <Header queryParams={props.location.query} isPoliciesPage={props.location.pathname.startsWith('/policies')}/>
    <main className="mdl-layout__content" style={{ marginTop:'-20px' }}>
      <div className="page-content">
        { props.children }
      </div>
    </main>
  </div>
);

RootComponent.propTypes = {
  children: React.PropTypes.node,
};

export default BaseComponent(RootComponent);
