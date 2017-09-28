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
  <div>
    <Header />
    <main style={{ paddingTop: 64, position: 'relative', zIndex: 1 }}>
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
